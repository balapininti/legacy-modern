package com.modtoolkit.parsers.cobol;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns expanded source into text ProLeap accepts with line numbers intact. ProLeap's own preprocessor merges
 * continuation lines (shifting every later line), treats debugging lines as live code and has no token for
 * EXEC DLI, so those three are settled here. Output has exactly one line per expanded line.
 */
public final class ParserInput {
    private static final Pattern EXEC = Pattern.compile("(?i)(?<![\\w-])EXEC(?:UTE)?\\s+([A-Z]+)\\b");
    private static final Pattern EXEC_ALONE = Pattern.compile("(?i)(?<![\\w-])EXEC(?:UTE)?\\s*$");
    private static final Pattern END_EXEC = Pattern.compile("(?i)END-EXEC");
    private static final Pattern REPOSITORY = Pattern.compile("(?i)^\\s{6}.\\s*REPOSITORY\\s*\\.");
    private static final Pattern NEXT_AFTER_REPOSITORY = Pattern.compile("(?i)^\\s{6}.\\s*((INPUT-OUTPUT|FILE|WORKING-STORAGE|LINKAGE|LOCAL-STORAGE)\\s+SECTION|(DATA|PROCEDURE)\\s+DIVISION|SPECIAL-NAMES|FILE-CONTROL)\\b");
    private static final Pattern DEBUG_MODE = Pattern.compile("(?i)WITH\\s+DEBUGGING\\s+MODE");

    public record Result(List<String> lines, List<ExecBlock> execs) {}

    private ParserInput() {}

    public static Result prepare(ExpandedSource src) {
        boolean debug = src.lines().stream().anyMatch(l -> DEBUG_MODE.matcher(l).find() && !comment(l, src.free()));
        List<String> l = new ArrayList<>(src.lines().size());
        for (String raw : src.lines()) {
            l.add(toFixedShape(raw, src.free(), debug));
        }
        foldContinuations(l);
        blankRepository(l);
        List<ExecBlock> execs = collapseExecs(l);
        return new Result(l, execs);
    }

    /** Enterprise COBOL's REPOSITORY paragraph names classes and functions; it holds no facts and the grammar lacks it. */
    private static void blankRepository(List<String> l) {
        for (int i = 0; i < l.size(); i++) {
            if (!REPOSITORY.matcher(l.get(i)).find()) continue;
            int j = i;
            while (j < l.size() && !NEXT_AFTER_REPOSITORY.matcher(l.get(j)).find()) l.set(j++, "");
            i = j;
        }
    }

    private static boolean comment(String l, boolean free) {
        return (!free && l.length() > 6 && "*/$".indexOf(l.charAt(6)) >= 0) || l.stripLeading().startsWith("*>");
    }

    /** Blanks anything that is not code and brings free-format lines to the seven column prefix ProLeap reads. */
    private static String toFixedShape(String l, boolean free, boolean debug) {
        if (free) {
            String t = l.stripLeading();
            if (t.startsWith("*>") || t.startsWith(">>") || t.startsWith("$")) return "";
            int inline = inlineComment(l);
            return "       " + (inline >= 0 ? l.substring(0, inline) : l);
        }
        if (l.length() < 7) return "";
        char ind = l.charAt(6);
        if ("*/$".indexOf(ind) >= 0) return "";
        if (ind == 'D' || ind == 'd') return debug ? "      " + " " + l.substring(7) : "";
        String t = l.substring(7).stripLeading();
        if (t.startsWith("*>") || t.startsWith(">>")) return "";
        int inline = inlineComment(l);
        String body = inline >= 0 ? l.substring(0, inline) : l;
        return "      " + (ind == '-' ? '-' : ' ') + (body.length() > 7 ? body.substring(7) : "");
    }

    private static int inlineComment(String l) {
        char q = 0;
        for (int i = 0; i + 1 < l.length(); i++) {
            char c = l.charAt(i);
            if (q != 0) { if (c == q) q = 0; continue; }
            if (c == '"' || c == '\'') { q = c; continue; }
            if (c == '*' && l.charAt(i + 1) == '>') return i;
        }
        return -1;
    }

    /** Moves each continuation onto the line it continues and leaves a blank in its place. */
    private static void foldContinuations(List<String> l) {
        int prev = -1;
        for (int i = 0; i < l.size(); i++) {
            String s = l.get(i);
            if (s.isBlank()) continue;
            if (s.length() > 6 && s.charAt(6) == '-' && prev >= 0) {
                String cont = s.substring(7).stripLeading();
                String p = l.get(prev);
                char open = openLiteral(p);
                if (open != 0 && !cont.isEmpty() && cont.charAt(0) == open) {
                    // Literal continues: the previous line keeps its spaces to column 72, per the COBOL rules.
                    String pad = p.length() < 72 ? " ".repeat(72 - p.length()) : "";
                    l.set(prev, p + pad + cont.substring(1));
                } else {
                    l.set(prev, p.stripTrailing() + (open != 0 ? "" : " ") + cont);
                }
                l.set(i, "");
                continue;
            }
            prev = i;
        }
    }

    private static char openLiteral(String line) {
        char q = 0;
        for (int i = 7; i < line.length(); i++) {
            char c = line.charAt(i);
            if (q != 0) { if (c == q) q = 0; continue; }
            if (c == '"' || c == '\'') q = c;
        }
        return q;
    }

    private static List<ExecBlock> collapseExecs(List<String> l) {
        List<ExecBlock> out = new ArrayList<>();
        for (int i = 0; i < l.size(); i++) {
            String s = l.get(i);
            Matcher m = EXEC.matcher(s);
            boolean found = m.find() && outsideLiteral(s, m.start());
            String kind;
            int bodyStart;
            if (found) {
                kind = m.group(1).toUpperCase(Locale.ROOT);
                bodyStart = m.start();
            } else {
                Matcher alone = EXEC_ALONE.matcher(s);
                if (!alone.find() || !outsideLiteral(s, alone.start())) continue;
                int n = nextCode(l, i);
                if (n < 0) continue;
                kind = l.get(n).strip().split("\\s+")[0].toUpperCase(Locale.ROOT);
                bodyStart = alone.start();
            }
            StringBuilder body = new StringBuilder(s.substring(bodyStart));
            int j = i;
            Matcher e = END_EXEC.matcher(body);
            while (!e.find() && j + 1 < l.size() && j - i < 5000) {
                j++;
                body.append(' ').append(l.get(j).length() > 7 ? l.get(j).substring(7) : "");
                e = END_EXEC.matcher(body);
            }
            e = END_EXEC.matcher(body);
            if (!e.find()) continue; // Unterminated: leave it for the parser to report.
            String text = body.substring(0, e.start()).replaceAll("\\s+", " ").strip();
            String tail = body.substring(e.end());
            out.add(new ExecBlock(kind, i + 1, j + 1, text));
            // Keep the parser happy with one token per block; SQL stays SQL since only it is legal in the data division.
            String tag = kind.equals("SQL") ? "EXEC SQL X END-EXEC" : "EXEC CICS X END-EXEC";
            l.set(i, s.substring(0, bodyStart) + tag + tail);
            for (int k = i + 1; k <= j; k++) l.set(k, "");
            i = j;
        }
        return out;
    }

    private static int nextCode(List<String> l, int i) {
        for (int k = i + 1; k < l.size() && k < i + 5; k++) {
            if (!l.get(k).isBlank()) return k;
        }
        return -1;
    }

    private static boolean outsideLiteral(String s, int at) {
        String b = s.substring(0, at);
        return b.chars().filter(c -> c == '\'').count() % 2 == 0 && b.chars().filter(c -> c == '"').count() % 2 == 0;
    }
}
