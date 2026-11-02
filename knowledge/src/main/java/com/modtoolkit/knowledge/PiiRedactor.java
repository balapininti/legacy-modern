package com.modtoolkit.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Replaces matches of the client's patterns before any prompt is sent. Same length, so line layout is unchanged. */
public final class PiiRedactor {
    private final List<Pattern> patterns = new ArrayList<>();

    public PiiRedactor(boolean enabled, List<String> regexes) {
        if (enabled && regexes != null) for (String r : regexes) patterns.add(Pattern.compile(r));
    }

    public String redact(String text) {
        String out = text;
        for (Pattern p : patterns) out = p.matcher(out).replaceAll(m -> "#".repeat(m.group().length()));
        return out;
    }
}
