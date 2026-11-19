package com.fortivance.evidence;

import com.fortivance.ai.AiGateway;
import com.fortivance.incident.TextExtractor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a document into citable passages (no tokens spent).
 * Key format: p{page}-c{n}. Each numbered clause, paragraph or table becomes one passage.
 */
@Service
public class PassageService {

    public record Passage(long id, String key, int page, String sectionRef, String text) { }

    private static final Pattern CLAUSE = Pattern.compile("^\\s*(\\d+(?:\\.\\d+)+)\\s+(.*)$");
    private static final int MAX_LEN = 1200;

    private final JdbcTemplate jdbc;
    private final TextExtractor extractor;
    private final String docsPath;

    public PassageService(JdbcTemplate jdbc, TextExtractor extractor,
                          @Value("${fortivance.sample-docs-path:./sample-docs}") String docsPath) {
        this.jdbc = jdbc;
        this.extractor = extractor;
        this.docsPath = docsPath;
    }

    public List<Passage> forDocument(long documentId) {
        List<Passage> existing = load(documentId);
        if (!existing.isEmpty()) return existing;

        Map<String, Object> doc = jdbc.queryForMap("SELECT file_path, extracted_text FROM documents WHERE id = ?", documentId);
        List<String> pages;
        byte[] bytes = null;
        String stored = (String) doc.get("extracted_text");
        if (stored != null && !stored.isBlank()) {
            pages = List.of(stored);
        } else {
            Path file = Path.of(docsPath).resolve((String) doc.get("file_path")).normalize();
            if (!file.startsWith(Path.of(docsPath).normalize())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid document path");
            }
            try {
                bytes = Files.readAllBytes(file);
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document file not found: " + file);
            }
            pages = extractor.extract(file.getFileName().toString(), bytes).pages();
        }

        int page = 0;
        for (String pageText : pages) {
            page++;
            int n = 0;
            for (String[] chunk : chunk(pageText)) {
                n++;
                jdbc.update("INSERT INTO document_passages (document_id, passage_key, page_no, section_ref, text) VALUES (?,?,?,?,?)",
                        documentId, "p" + page + "-c" + n, page, chunk[0], chunk[1]);
            }
        }
        String fullText = String.join("\n", pages);
        jdbc.update("UPDATE documents SET extracted_text = COALESCE(extracted_text, ?), page_count = ?, " +
                        "content_hash = COALESCE(content_hash, ?), processing_status = 'EXTRACTED' WHERE id = ?",
                fullText, pages.size(), AiGateway.sha256(bytes != null ? new String(bytes) : fullText), documentId);
        return load(documentId);
    }

    public List<Passage> load(long documentId) {
        return jdbc.query("SELECT id, passage_key, page_no, section_ref, text FROM document_passages WHERE document_id = ? ORDER BY id",
                (rs, i) -> new Passage(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getString(5)),
                documentId);
    }

    /** Returns [sectionRef, text] pairs. */
    static List<String[]> chunk(String text) {
        List<String[]> out = new ArrayList<>();
        String heading = null;
        String ref = null;
        StringBuilder buf = new StringBuilder();
        boolean inTable = false;

        for (String raw : text.replace("\r\n", "\n").split("\n")) {
            String line = raw.strip();
            boolean isTable = line.startsWith("|");
            boolean blank = line.isEmpty() || line.equals("---");
            Matcher m = CLAUSE.matcher(line);

            // Markdown headings, and bold-only lines such as "**Schedule C - Approved Subcontractors**"
            if (line.startsWith("#") || (line.startsWith("**") && line.endsWith("**") && line.length() > 4 && !line.contains(":**"))) {
                flush(out, ref, buf);
                heading = line.replaceAll("^#+\\s*", "").replace("*", "").trim();
                ref = heading;
                continue;
            }
            if (blank || (inTable && !isTable)) {
                flush(out, ref, buf);
                inTable = false;
                ref = heading;
                if (blank) continue;
            }
            if (m.matches()) {
                flush(out, ref, buf);
                ref = "Clause " + m.group(1) + (heading != null ? " (" + heading + ")" : "");
            }
            if (isTable) inTable = true;
            if (buf.length() > 0) buf.append(isTable ? "\n" : " ");
            buf.append(line.replace("**", ""));
            if (buf.length() > MAX_LEN) flush(out, ref, buf);
        }
        flush(out, ref, buf);
        return out;
    }

    private static void flush(List<String[]> out, String ref, StringBuilder buf) {
        String t = buf.toString().trim();
        if (t.length() >= 20) {
            String r = ref == null ? null : (ref.length() > 100 ? ref.substring(0, 100) : ref);
            out.add(new String[]{r, t});
        }
        buf.setLength(0);
    }
}
