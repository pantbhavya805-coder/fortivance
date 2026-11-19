package com.fortivance.incident;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Local text extraction (no tokens spent). PDF via PDFBox, otherwise UTF-8 text/markdown. */
@Component
public class TextExtractor {

    public record Extracted(String text, List<String> pages) { }

    public Extracted extract(String filename, byte[] bytes) {
        boolean pdf = (filename != null && filename.toLowerCase().endsWith(".pdf"))
                || (bytes.length > 4 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F');
        if (!pdf) {
            String t = new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n");
            return new Extracted(t, List.of(t));
        }
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            List<String> pages = new ArrayList<>();
            for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                pages.add(stripper.getText(doc));
            }
            return new Extracted(String.join("\n", pages), pages);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not read PDF: " + e.getMessage());
        }
    }
}
