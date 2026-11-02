package com.modtoolkit.parsers.normalize;

import java.util.ArrayList;
import java.util.List;

/**
 * A member decoded to UTF-8. Normalized lines correspond 1:1 to original lines or records, so line provenance
 * is exact; {@link #originalLine} exists so that a future normalization step can change that without changing callers.
 */
public record NormalizedMember(String encoding, Format format, List<String> lines, String sha256, boolean binary) {
    public enum Format { FIXED, FREE }

    public int originalLine(int normalizedLine) {
        return normalizedLine;
    }

    /**
     * One fixed-format line as the COBOL tools see it. A comment in column 1 is Micro Focus's terminal format. Code past
     * column 72 is the identification area, unless cutting there would split a literal or a call: dialects with longer
     * lines, and sources that a loose compiler accepted, do this, and the rest of the line is really code.
     */
    public static String fixedLine(String l) {
        if (!l.isEmpty() && (l.charAt(0) == '*' || l.charAt(0) == '/')) return "      *" + l.substring(1);
        String cut = l.length() > 72 && !splitsCode(l) ? l.substring(0, 72) : l;
        return cut.length() <= 6 ? "" : "      " + cut.substring(6).stripTrailing();
    }

    private static boolean splitsCode(String l) {
        if (l.length() > 6 && "*/".indexOf(l.charAt(6)) >= 0) return false;
        char q = 0;
        int depth = 0;
        for (int i = 7; i < 72; i++) {
            char c = l.charAt(i);
            if (q != 0) {
                if (c == q) q = 0;
            } else if (c == '\'' || c == '"') {
                q = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
        }
        if (q != 0) return l.indexOf(q, 72) >= 0; // Open literal, closed in what would be cut.
        return depth > 0 && l.indexOf(')', 72) >= 0;
    }

    /** Lines as the COBOL tools see them: fixed format keeps its column positions with 1-6 blanked and 73+ cut. */
    public List<String> cobolLines() {
        if (format == Format.FREE) {
            return lines;
        }
        List<String> out = new ArrayList<>(lines.size());
        for (String l : lines) out.add(fixedLine(l));
        return out;
    }
}
