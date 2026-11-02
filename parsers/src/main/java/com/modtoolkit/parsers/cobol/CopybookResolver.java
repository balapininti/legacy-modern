package com.modtoolkit.parsers.cobol;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Expands COPY and EXEC SQL INCLUDE line-preservingly: a statement's own lines stay, blanked, with their origin. */
public final class CopybookResolver {
    private static final int MAX_DEPTH = 32;
    private static final Pattern COPY = Pattern.compile("(?i)(?<![\\w-])COPY\\s+(?:\"([^\"]+)\"|'([^']+)'|([A-Z0-9@#$][-\\w@#$]*))");
    private static final Pattern COPY_START = Pattern.compile("(?i)(?<![\\w-])COPY(\\s|$)");
    private static final Pattern INCLUDE = Pattern.compile("(?is)EXEC\\s+SQL\\s+INCLUDE\\s+([\\w@#$-]+)\\s+END-EXEC\\s*\\.?");
    private static final Pattern INCLUDE_START = Pattern.compile("(?i)EXEC\\s+SQL\\s+INCLUDE\\b|EXEC\\s+SQL\\s*$");
    private static final Pattern PSEUDO_PAIR = Pattern.compile("(?is)(?:(LEADING|TRAILING)\\s+)?(==.*?==|\"[^\"]*\"|'[^']*'|[^\\s.]+)\\s+BY\\s+(==.*?==|\"[^\"]*\"|'[^']*'|[^\\s.]+)");

    private final CopybookLibrary lib;
    private final boolean free;
    private List<String> out;
    private List<Loc> origins;
    private List<ExpandedSource.CopyUse> copies;
    private List<String> missing;
    private Map<String, ExpandedSource.Duplicate> dups;

    private record Replacement(Pattern pattern, String with) {}

    private CopybookResolver(CopybookLibrary lib, boolean free) {
        this.lib = lib;
        this.free = free;
    }

    public static ExpandedSource expand(String member, List<String> lines, CopybookLibrary lib, boolean free) {
        CopybookResolver r = new CopybookResolver(lib, free);
        r.out = new ArrayList<>(lines.size());
        r.origins = new ArrayList<>(lines.size());
        r.copies = new ArrayList<>();
        r.missing = new ArrayList<>();
        r.dups = new LinkedHashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(member.toUpperCase(Locale.ROOT));
        r.walk(member, lines, List.of(), stack);
        return new ExpandedSource(member, free, r.out, r.origins, r.copies, r.missing, List.copyOf(r.dups.values()));
    }

    private void walk(String member, List<String> lines, List<Replacement> repl, Deque<String> stack) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (isComment(line)) {
                emit(line, member, i + 1);
                continue;
            }
            String code = apply(line, repl);
            int copyAt = findOutsideLiteral(COPY_START, code);
            int incAt = findOutsideLiteral(INCLUDE_START, code);
            if (copyAt < 0 && incAt < 0) {
                emit(code, member, i + 1);
                continue;
            }
            // Gather the whole statement, which may span lines, up to its terminator.
            StringBuilder stmt = new StringBuilder();
            int start = copyAt >= 0 && (incAt < 0 || copyAt < incAt) ? copyAt : incAt;
            boolean isInclude = incAt >= 0 && start == incAt;
            stmt.append(code.substring(start));
            int j = i;
            while (!complete(stmt, isInclude) && j + 1 < lines.size() && j - i < 200) {
                j++;
                String next = lines.get(j);
                if (!isComment(next)) stmt.append(' ').append(codeArea(next));
            }
            if (isInclude && !INCLUDE.matcher(stmt).find()) {
                emit(code, member, i + 1); // An ordinary EXEC SQL that merely starts at the end of the line.
                continue;
            }
            emit(code.substring(0, start), member, i + 1);
            for (int k = i + 1; k <= j; k++) emit("", member, k + 1);
            handle(stmt.toString(), isInclude, member, i + 1, repl, stack);
            i = j;
        }
    }

    private void handle(String stmt, boolean include, String member, int line, List<Replacement> outer, Deque<String> stack) {
        String name;
        Map<String, String> replacing = new LinkedHashMap<>();
        List<Replacement> mine = new ArrayList<>(outer);
        if (include) {
            Matcher m = INCLUDE.matcher(stmt);
            if (!m.find()) return;
            name = m.group(1);
        } else {
            Matcher m = COPY.matcher(stmt);
            if (!m.find()) return;
            name = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
            String up = stmt.toUpperCase(Locale.ROOT);
            int r = up.indexOf("REPLACING");
            if (r >= 0) {
                Matcher p = PSEUDO_PAIR.matcher(stmt.substring(r + "REPLACING".length()));
                while (p.find()) {
                    replacing.put(p.group(2), p.group(3));
                    mine.add(replacement(p.group(1), p.group(2), p.group(3)));
                }
            }
        }
        name = name.replaceAll("(?i)\\.(cpy|cbl|cob|copy)$", "");
        String upper = name.toUpperCase(Locale.ROOT);
        boolean system = CopybookLibrary.SYSTEM.contains(upper);
        var entry = lib.resolve(name);
        copies.add(new ExpandedSource.CopyUse(name, new Loc(member, line), replacing, system, entry.isPresent(), entry.map(CopybookLibrary.Entry::path).orElse(null)));
        if (entry.isEmpty()) {
            if (!system) missing.add(upper);
            return;
        }
        List<String> d = lib.duplicates(name);
        if (!d.isEmpty()) dups.put(upper, new ExpandedSource.Duplicate(upper, d));
        if (stack.contains(upper) || stack.size() >= MAX_DEPTH) {
            missing.add(upper + " (cycle)");
            return;
        }
        stack.push(upper);
        walk(entry.get().name(), entry.get().lines(), mine, stack);
        stack.pop();
    }

    private Replacement replacement(String mode, String from, String to) {
        String f = unwrap(from).strip().replaceAll("\\s+", " ");
        String t = unwrap(to);
        String quoted = Pattern.quote(f).replace(" ", "\\E\\s+\\Q");
        String wordChars = "[\\w-]";
        String regex;
        if ("LEADING".equalsIgnoreCase(mode)) {
            regex = "(?<!" + wordChars + ")" + quoted;
        } else if ("TRAILING".equalsIgnoreCase(mode)) {
            regex = quoted + "(?!" + wordChars + ")";
        } else if (f.matches("[\\w-]+")) {
            regex = "(?<!" + wordChars + ")" + quoted + "(?!" + wordChars + ")";
        } else {
            regex = quoted; // Tag-style pseudo-text such as :TAG: may sit inside a word.
        }
        return new Replacement(Pattern.compile(regex, Pattern.CASE_INSENSITIVE), Matcher.quoteReplacement(t));
    }

    private static String unwrap(String s) {
        if (s.startsWith("==") && s.endsWith("==") && s.length() >= 4) return s.substring(2, s.length() - 2);
        return s;
    }

    private String apply(String line, List<Replacement> repl) {
        String out2 = line;
        for (Replacement r : repl) out2 = r.pattern().matcher(out2).replaceAll(r.with());
        return out2;
    }

    private static boolean complete(StringBuilder s, boolean include) {
        if (include) return s.toString().toUpperCase(Locale.ROOT).contains("END-EXEC");
        boolean inPseudo = false;
        char q = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (q != 0) { if (c == q) q = 0; continue; }
            if (c == '"' || c == '\'') { q = c; continue; }
            if (c == '=' && i + 1 < s.length() && s.charAt(i + 1) == '=') { inPseudo = !inPseudo; i++; continue; }
            if (c == '.' && !inPseudo && (i + 1 == s.length() || Character.isWhitespace(s.charAt(i + 1)))) return true;
        }
        return false;
    }

    private static int findOutsideLiteral(Pattern p, String line) {
        Matcher m = p.matcher(line);
        while (m.find()) {
            String before = line.substring(0, m.start());
            long sq = before.chars().filter(c -> c == '\'').count(), dq = before.chars().filter(c -> c == '"').count();
            if (sq % 2 == 0 && dq % 2 == 0) return m.start();
        }
        return -1;
    }

    private boolean isComment(String l) {
        if (!free && l.length() > 6 && "*/$".indexOf(l.charAt(6)) >= 0) return true;
        return l.stripLeading().startsWith("*>");
    }

    private String codeArea(String l) {
        return free || l.length() <= 7 ? l : l.substring(7);
    }

    private void emit(String text, String member, int line) {
        out.add(text);
        origins.add(new Loc(member, line));
    }
}
