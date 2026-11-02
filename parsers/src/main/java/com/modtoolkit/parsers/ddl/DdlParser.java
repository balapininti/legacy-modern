package com.modtoolkit.parsers.ddl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** CREATE TABLE and key clauses from DB2 DDL. Other statements are skipped without complaint. */
public final class DdlParser {
    public record Column(String name, String type, boolean nullable, Integer keyPos, int line) {}
    public record Table(String schema, String name, int line, List<Column> columns) {}

    private static final Pattern CREATE = Pattern.compile("(?is)\\bCREATE\\s+TABLE\\s+(?:([A-Z0-9_#@$\"]+)\\.)?([A-Z0-9_#@$\"]+)\\s*\\(");
    private static final Pattern ALTER_PK = Pattern.compile("(?is)\\bALTER\\s+TABLE\\s+(?:[A-Z0-9_#@$\"]+\\.)?([A-Z0-9_#@$\"]+)\\s+ADD\\s+(?:CONSTRAINT\\s+\\S+\\s+)?PRIMARY\\s+KEY\\s*\\(([^)]*)\\)");
    private static final Pattern PK = Pattern.compile("(?is)^(?:CONSTRAINT\\s+\\S+\\s+)?PRIMARY\\s+KEY\\s*\\(([^)]*)\\)");
    private static final Pattern NON_COLUMN = Pattern.compile("(?is)^(CONSTRAINT|PRIMARY|FOREIGN|UNIQUE|CHECK|PERIOD)\\b");

    private DdlParser() {}

    public static List<Table> parse(String text) {
        String t = stripComments(text);
        Map<String, Table> tables = new LinkedHashMap<>();
        Matcher m = CREATE.matcher(t);
        while (m.find()) {
            int open = m.end() - 1;
            int close = matching(t, open);
            if (close < 0) continue;
            String name = clean(m.group(2));
            List<Column> cols = new ArrayList<>();
            List<String> keys = new ArrayList<>();
            int line = lineOf(t, m.start());
            int pos = open + 1;
            for (String part : splitTop(t.substring(open + 1, close))) {
                String p = part.strip();
                int lineHere = lineOf(t, pos + part.indexOf(p.isEmpty() ? " " : p.substring(0, 1)));
                pos += part.length() + 1;
                if (p.isEmpty()) continue;
                Matcher pk = PK.matcher(p);
                if (pk.find()) {
                    for (String k : pk.group(1).split(",")) keys.add(clean(k.strip().split("\\s+")[0]));
                    continue;
                }
                if (NON_COLUMN.matcher(p).find()) continue;
                String[] w = p.split("\\s+", 2);
                String rest = w.length > 1 ? w[1] : "";
                String up = rest.toUpperCase(Locale.ROOT);
                boolean inlinePk = up.contains("PRIMARY KEY");
                if (inlinePk) keys.add(clean(w[0]));
                cols.add(new Column(clean(w[0]), type(rest), !up.contains("NOT NULL") && !inlinePk, null, lineHere));
            }
            Table tb = new Table(m.group(1) == null ? null : clean(m.group(1)), name, line, cols);
            applyKeys(tb, keys);
            tables.put(name, tb);
        }
        Matcher a = ALTER_PK.matcher(t);
        while (a.find()) {
            Table tb = tables.get(clean(a.group(1)));
            if (tb == null) continue;
            List<String> keys = new ArrayList<>();
            for (String k : a.group(2).split(",")) keys.add(clean(k.strip().split("\\s+")[0]));
            applyKeys(tb, keys);
        }
        return new ArrayList<>(tables.values());
    }

    private static void applyKeys(Table t, List<String> keys) {
        for (int i = 0; i < t.columns().size(); i++) {
            Column c = t.columns().get(i);
            int k = keys.indexOf(c.name());
            if (k >= 0) t.columns().set(i, new Column(c.name(), c.type(), c.nullable(), k + 1, c.line()));
        }
    }

    private static String type(String rest) {
        Matcher m = Pattern.compile("(?is)^([A-Z][A-Z0-9_ ]*?(?:\\s*\\([^)]*\\))?)(?=\\s+(?:NOT|NULL|WITH|DEFAULT|PRIMARY|UNIQUE|GENERATED|REFERENCES|CHECK|CONSTRAINT|FOR)\\b|\\s*$)").matcher(rest.strip());
        return m.find() ? m.group(1).replaceAll("\\s+", " ").toUpperCase(Locale.ROOT) : rest.strip().split("\\s+")[0].toUpperCase(Locale.ROOT);
    }

    /** Blank out comments in place so offsets, and so line numbers, stay true. */
    private static String stripComments(String s) {
        char[] c = s.toCharArray();
        boolean q = false;
        for (int i = 0; i < c.length; i++) {
            if (c[i] == '\'') q = !q;
            if (q) continue;
            if (c[i] == '-' && i + 1 < c.length && c[i + 1] == '-') {
                while (i < c.length && c[i] != '\n') c[i++] = ' ';
            } else if (c[i] == '/' && i + 1 < c.length && c[i + 1] == '*') {
                while (i < c.length && !(c[i] == '*' && i + 1 < c.length && c[i + 1] == '/')) {
                    if (c[i] != '\n') c[i] = ' ';
                    i++;
                }
                if (i < c.length) c[i] = ' ';
                if (i + 1 < c.length) c[++i] = ' ';
            }
        }
        return new String(c);
    }

    private static int matching(String s, int open) {
        int d = 0;
        boolean q = false;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') q = !q;
            if (q) continue;
            if (c == '(') d++;
            if (c == ')' && --d == 0) return i;
        }
        return -1;
    }

    private static List<String> splitTop(String s) {
        List<String> out = new ArrayList<>();
        int d = 0, last = 0;
        boolean q = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') q = !q;
            if (q) continue;
            if (c == '(') d++;
            if (c == ')') d--;
            if (c == ',' && d == 0) {
                out.add(s.substring(last, i));
                last = i + 1;
            }
        }
        out.add(s.substring(last));
        return out;
    }

    private static int lineOf(String s, int at) {
        int n = 1;
        for (int i = 0; i < at && i < s.length(); i++) if (s.charAt(i) == '\n') n++;
        return n;
    }

    private static String clean(String s) {
        return s.replace("\"", "").toUpperCase(Locale.ROOT);
    }
}
