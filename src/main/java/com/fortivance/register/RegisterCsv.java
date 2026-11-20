package com.fortivance.register;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal CSV reader for the register templates (RFC 4180: quoted cells, doubled quotes, line breaks inside
 * quotes). Accepts what Excel writes: a UTF-8 byte-order mark, CRLF line ends, and comma or semicolon as the
 * delimiter (Excel uses semicolons in some locales). The delimiter is taken from the header line.
 */
final class RegisterCsv {

    /** One data row: its first physical line number in the file and its cells by normalised header name. */
    record Row(int line, Map<String, String> cells) {
        String get(String column) {
            String v = cells.get(column);
            return v == null ? "" : v.strip();
        }
    }

    record Table(List<String> headers, List<Row> rows) { }

    static final class CsvException extends Exception {
        CsvException(String message) {
            super(message);
        }
    }

    private RegisterCsv() {
    }

    static String normaliseHeader(String h) {
        return h.strip().toLowerCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
    }

    static Table parse(String text, int maxRows) throws CsvException {
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        if (text.isBlank()) {
            throw new CsvException("The file is empty.");
        }
        int firstBreak = text.indexOf('\n');
        String headerLine = firstBreak < 0 ? text : text.substring(0, firstBreak);
        char delimiter = count(headerLine, ';') > count(headerLine, ',') ? ';' : ',';

        List<List<String>> records = new ArrayList<>();
        List<Integer> startLines = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        int line = 1;
        int recordStart = 1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    if (c == '\n') line++;
                    cell.append(c);
                }
            } else if (c == '"' && cell.length() == 0) {
                quoted = true;
            } else if (c == delimiter) {
                current.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\r') {
                // ignored; the following \n ends the record
            } else if (c == '\n') {
                current.add(cell.toString());
                cell.setLength(0);
                records.add(current);
                startLines.add(recordStart);
                current = new ArrayList<>();
                line++;
                recordStart = line;
            } else {
                cell.append(c);
            }
        }
        if (quoted) {
            throw new CsvException("A quoted cell starting on line " + recordStart + " is never closed.");
        }
        if (cell.length() > 0 || !current.isEmpty()) {
            current.add(cell.toString());
            records.add(current);
            startLines.add(recordStart);
        }

        List<String> headers = records.get(0).stream().map(RegisterCsv::normaliseHeader).toList();
        List<Row> rows = new ArrayList<>();
        for (int r = 1; r < records.size(); r++) {
            List<String> rec = records.get(r);
            if (rec.stream().allMatch(String::isBlank)) {
                continue; // blank lines, including Excel's trailing ",,,,"
            }
            if (rows.size() >= maxRows) {
                throw new CsvException("The file has more than " + maxRows + " rows. Split it into smaller files.");
            }
            Map<String, String> cells = new LinkedHashMap<>();
            for (int c = 0; c < headers.size(); c++) {
                cells.put(headers.get(c), c < rec.size() ? rec.get(c) : "");
            }
            rows.add(new Row(startLines.get(r), cells));
        }
        return new Table(headers, rows);
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) n++;
        }
        return n;
    }
}
