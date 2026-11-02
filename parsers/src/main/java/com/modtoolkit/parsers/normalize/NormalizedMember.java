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

    /** Lines as the COBOL tools see them: fixed format keeps its column positions with 1-6 blanked and 73+ cut. */
    public List<String> cobolLines() {
        if (format == Format.FREE) {
            return lines;
        }
        List<String> out = new ArrayList<>(lines.size());
        for (String l : lines) {
            String cut = l.length() > 72 ? l.substring(0, 72) : l;
            out.add(cut.length() <= 6 ? "" : "      " + cut.substring(6).stripTrailing());
        }
        return out;
    }
}
