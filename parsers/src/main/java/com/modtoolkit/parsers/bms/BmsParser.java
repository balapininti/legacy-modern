package com.modtoolkit.parsers.bms;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the three BMS macros, DFHMSD, DFHMDI and DFHMDF, across column 72 and trailing-comma continuations. */
public final class BmsParser {
    public record Field(String name, Integer row, Integer col, Integer length, Map<String, String> attrs, int line) {}
    public record BmsMap(String mapset, String name, int line, Map<String, String> attrs, List<Field> fields) {}

    private static final Pattern POS = Pattern.compile("\\(?\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)?");

    private BmsParser() {}

    public static List<BmsMap> parse(List<String> lines) {
        List<BmsMap> out = new ArrayList<>();
        String mapset = null;
        BmsMap cur = null;
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            if (raw.startsWith("*") || raw.isBlank()) continue;
            int startLine = i + 1;
            StringBuilder stmt = new StringBuilder(body(raw, false));
            while (continued(lines.get(i)) && i + 1 < lines.size()) {
                i++;
                boolean inLiteral = stmt.chars().filter(c -> c == '\'').count() % 2 == 1;
                stmt.append(inLiteral ? body(lines.get(i), true).substring(Math.min(15, body(lines.get(i), true).length())) : stripCol(lines.get(i)).strip());
                stmt = new StringBuilder(body(stmt.toString(), true));
            }
            String s = stmt.toString();
            String[] head = s.strip().split("\\s+", 3);
            String name, macro, rest;
            if (s.charAt(0) == ' ') {
                name = null;
                macro = head[0];
                rest = head.length > 1 ? s.strip().substring(head[0].length()).strip() : "";
            } else {
                name = head[0];
                macro = head.length > 1 ? head[1] : "";
                rest = head.length > 2 ? head[2] : "";
            }
            Map<String, String> p = params(rest);
            switch (macro.toUpperCase(Locale.ROOT)) {
                case "DFHMSD" -> {
                    if (!"FINAL".equalsIgnoreCase(p.get("TYPE"))) mapset = name == null ? mapset : name.toUpperCase(Locale.ROOT);
                }
                case "DFHMDI" -> {
                    cur = new BmsMap(mapset, name == null ? "" : name.toUpperCase(Locale.ROOT), startLine, p, new ArrayList<>());
                    out.add(cur);
                }
                case "DFHMDF" -> {
                    if (cur == null) break;
                    Integer row = null, col = null;
                    String pos = p.get("POS");
                    if (pos != null) {
                        Matcher m = POS.matcher(pos);
                        if (m.find()) {
                            row = Integer.valueOf(m.group(1));
                            col = Integer.valueOf(m.group(2));
                        }
                    }
                    Integer len = null;
                    if (p.get("LENGTH") != null && p.get("LENGTH").matches("\\d+")) len = Integer.valueOf(p.get("LENGTH"));
                    Map<String, String> attrs = new LinkedHashMap<>(p);
                    attrs.remove("POS");
                    attrs.remove("LENGTH");
                    cur.fields().add(new Field(name == null ? null : name.toUpperCase(Locale.ROOT), row, col, len, attrs, startLine));
                }
                default -> { }
            }
        }
        return out;
    }

    /** Padding before column 72 is not part of a statement, unless a quoted literal is still open. */
    private static String body(String l, boolean keepLength) {
        String c = keepLength ? l : stripCol(l);
        return c.chars().filter(x -> x == '\'').count() % 2 == 1 ? c : c.stripTrailing();
    }

    private static boolean continued(String l) {
        if (l.length() >= 72 && l.charAt(71) != ' ') return true;
        String t = stripCol(l).stripTrailing();
        return t.endsWith(",") ;
    }

    private static String stripCol(String l) {
        return l.length() > 71 ? l.substring(0, 71) : l;
    }

    private static Map<String, String> params(String s) {
        Map<String, String> out = new LinkedHashMap<>();
        int depth = 0, last = 0;
        boolean q = false;
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') q = !q;
            if (q) continue;
            if (c == '(') depth++;
            if (c == ')') depth--;
            if (c == ',' && depth == 0) {
                parts.add(s.substring(last, i));
                last = i + 1;
            }
            if (c == ' ' && depth == 0) {
                parts.add(s.substring(last, i));
                last = s.length();
                break;
            }
        }
        if (last < s.length()) parts.add(s.substring(last));
        for (String part : parts) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                String v = part.substring(eq + 1).strip();
                if (v.length() >= 2 && v.startsWith("'") && v.endsWith("'")) v = v.substring(1, v.length() - 1);
                out.put(part.substring(0, eq).strip().toUpperCase(Locale.ROOT), v);
            }
        }
        return out;
    }
}
