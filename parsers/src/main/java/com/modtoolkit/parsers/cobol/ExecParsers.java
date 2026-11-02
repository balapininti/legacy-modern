package com.modtoolkit.parsers.cobol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the text of EXEC blocks. Independent of the parser so every front end gets the same facts. */
public final class ExecParsers {
    private static final Pattern OPTION = Pattern.compile("([A-Za-z][\\w-]*)\\s*(?:\\(\\s*((?:[^()'\"]|'[^']*'|\"[^\"]*\"|\\([^()]*\\))*?)\\s*\\))?");
    private static final Pattern HOST = Pattern.compile("(?<![\\w:]):([A-Za-z][\\w-]*)(?:\\.([A-Za-z][\\w-]*))?");
    private static final Pattern TABLE_REF = Pattern.compile("(?i)\\b(FROM|JOIN|INTO|UPDATE)\\s+((?:[A-Za-z][\\w#@$]*\\.)?[A-Za-z][\\w#@$-]*)");
    private static final Pattern FROM_LIST = Pattern.compile("(?i)\\bFROM\\s+(.+?)(?=\\s+(?:WHERE|GROUP|ORDER|HAVING|FETCH|FOR|WITH|UNION|OPTIMIZE)\\b|$)");
    private static final Set<String> SQL_NOISE = Set.of("SELECT", "WHERE", "VALUES", "SET", "TABLE", "LATERAL", "FINAL", "NEW", "OLD", "SYSIBM");

    public record CicsCommand(String command, Map<String, String> options) {}

    private ExecParsers() {}

    public static CicsCommand cics(String text) {
        String body = text.replaceFirst("(?i)^EXEC(?:UTE)?\\s+CICS\\s*", "");
        Map<String, String> opts = new LinkedHashMap<>();
        Matcher m = OPTION.matcher(body);
        String cmd = null;
        while (m.find()) {
            if (m.group(0).isBlank()) continue;
            String k = m.group(1).toUpperCase(Locale.ROOT);
            if (cmd == null) {
                cmd = k;
                if (m.group(2) == null) continue;
            }
            opts.put(k, m.group(2) == null ? "" : m.group(2).strip());
        }
        return new CicsCommand(cmd == null ? "UNKNOWN" : cmd, opts);
    }

    /** The resource a CICS command acts on: kind and option value, or null when it has none. */
    public static String[] cicsResource(CicsCommand c) {
        String[][] order = {{"MAP", "map"}, {"PROGRAM", "program"}, {"TRANSID", "transaction"}, {"FILE", "file"}, {"DATASET", "file"}, {"QUEUE", "queue"}, {"QNAME", "queue"}, {"TD", "queue"}, {"TS", "queue"}};
        for (String[] o : order) {
            String v = c.options().get(o[0]);
            if (v != null && !v.isEmpty()) return new String[] {o[1], v};
        }
        return null;
    }

    public record SqlStatement(String type, List<String> tables, List<String> columns, List<String> hostVars) {}

    public static SqlStatement sql(String text) {
        String b = text.replaceFirst("(?i)^EXEC(?:UTE)?\\s+SQL\\s*", "").replaceAll("--[^\\n]*", " ").strip();
        String[] w = b.split("\\s+");
        String up = w[0].toUpperCase(Locale.ROOT);
        String type = up;
        if (up.equals("DECLARE") && b.toUpperCase(Locale.ROOT).matches(".*\\bTABLE\\b.*") && !b.toUpperCase(Locale.ROOT).contains("CURSOR")) {
            type = "DECLARE TABLE";
        } else if (up.equals("DECLARE") && b.toUpperCase(Locale.ROOT).contains("CURSOR")) {
            type = "DECLARE CURSOR";
        }
        Set<String> tables = new LinkedHashSet<>();
        Set<String> hosts = new LinkedHashSet<>();
        Matcher h = HOST.matcher(b);
        while (h.find()) hosts.add(h.group(1).toUpperCase(Locale.ROOT));
        if (type.equals("DECLARE TABLE")) {
            if (w.length > 1) tables.add(w[1].toUpperCase(Locale.ROOT));
            return new SqlStatement(type, List.copyOf(tables), declaredColumns(b), List.copyOf(hosts));
        }
        if (up.matches("SELECT|INSERT|UPDATE|DELETE|DECLARE|MERGE|WITH")) {
            Matcher t = TABLE_REF.matcher(b);
            while (t.find()) {
                // SELECT ... INTO names host variables, not a table; only INSERT INTO names one.
                if (t.group(1).equalsIgnoreCase("INTO") && !up.equals("INSERT") && !up.equals("MERGE")) continue;
                String name = t.group(2).toUpperCase(Locale.ROOT);
                if (!SQL_NOISE.contains(name) && !name.startsWith(":")) tables.add(name);
            }
            Matcher fl = FROM_LIST.matcher(b);
            while (fl.find()) {
                for (String part : fl.group(1).split(",")) {
                    String n = part.strip().split("\\s+")[0].toUpperCase(Locale.ROOT);
                    if (n.matches("[A-Z][\\w#@$.-]*") && !SQL_NOISE.contains(n)) tables.add(n);
                }
            }
        }
        return new SqlStatement(type, List.copyOf(tables), columns(up, b), List.copyOf(hosts));
    }

    private static List<String> declaredColumns(String b) {
        int open = b.indexOf('(');
        int close = b.lastIndexOf(')');
        if (open < 0 || close < open) return List.of();
        List<String> cols = new ArrayList<>();
        for (String c : splitTop(b.substring(open + 1, close))) {
            String n = c.strip().split("\\s+")[0].toUpperCase(Locale.ROOT);
            if (n.matches("[A-Z][\\w#@$-]*")) cols.add(n);
        }
        return cols;
    }

    private static List<String> columns(String verb, String b) {
        Set<String> cols = new LinkedHashSet<>();
        String up = b.toUpperCase(Locale.ROOT);
        String list = null;
        if (verb.equals("SELECT") || verb.equals("DECLARE")) {
            int s = up.indexOf("SELECT");
            int into = up.indexOf(" INTO ", Math.max(s, 0));
            int from = up.indexOf(" FROM ", Math.max(s, 0));
            int e = into > 0 && (from < 0 || into < from) ? into : from;
            if (s >= 0 && e > s) list = b.substring(s + 6, e);
        } else if (verb.equals("INSERT")) {
            int o = b.indexOf('('), c = b.indexOf(')', Math.max(o, 0));
            if (o > 0 && c > o && up.indexOf("VALUES") > c) list = b.substring(o + 1, c);
        } else if (verb.equals("UPDATE")) {
            Matcher m = Pattern.compile("(?i)\\bSET\\b(.*?)(\\bWHERE\\b|$)").matcher(b);
            if (m.find()) {
                for (String a : splitTop(m.group(1))) {
                    String n = a.split("=")[0].strip().toUpperCase(Locale.ROOT);
                    if (n.matches("[A-Z][\\w#@$.-]*")) cols.add(n);
                }
            }
        }
        if (list != null) {
            for (String c : splitTop(list)) {
                String n = c.strip().toUpperCase(Locale.ROOT);
                if (n.matches("[A-Z][\\w#@$-]*(\\.[A-Z][\\w#@$-]*)?") && !n.equals("DISTINCT")) cols.add(n);
            }
        }
        return List.copyOf(cols);
    }

    private static List<String> splitTop(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0, last = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            if (c == ')') depth--;
            if (c == ',' && depth == 0) { out.add(s.substring(last, i)); last = i + 1; }
        }
        out.add(s.substring(last));
        return out;
    }

    public static String literalOf(String optionValue) {
        if (optionValue == null) return null;
        String v = optionValue.strip();
        if (v.length() >= 2 && (v.startsWith("'") && v.endsWith("'") || v.startsWith("\"") && v.endsWith("\""))) {
            return v.substring(1, v.length() - 1).strip();
        }
        return null;
    }
}
