package com.modtoolkit.parsers.csd;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** DEFINE statements from a CICS CSD extract or a DFHCSDUP SYSIN: how transactions reach programs and files reach datasets. */
public final class CsdParser {
    public record Definition(String kind, String name, Map<String, String> attrs, int line) {}

    private static final Pattern DEFINE = Pattern.compile("(?i)^\\s*(?:DEFINE|DEF)\\s+([A-Z]+)\\s*\\(\\s*([^)\\s]+)\\s*\\)");
    private static final Pattern ATTR = Pattern.compile("(?i)\\b([A-Z][A-Z0-9]*)\\s*\\(\\s*([^()]*?)\\s*\\)");

    private CsdParser() {}

    public static List<Definition> parse(List<String> lines) {
        List<Definition> out = new ArrayList<>();
        String kind = null, name = null;
        int line = 0;
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.stripLeading().startsWith("*")) continue;
            Matcher m = DEFINE.matcher(l);
            if (m.find()) {
                if (kind != null) out.add(def(kind, name, body.toString(), line));
                kind = m.group(1).toUpperCase(Locale.ROOT);
                name = m.group(2).toUpperCase(Locale.ROOT);
                line = i + 1;
                body = new StringBuilder(l.substring(m.end()));
            } else if (kind != null) {
                if (l.startsWith("//") || l.startsWith("/*") || l.matches("(?i)^\\s*(LIST|ADD|DELETE|ALTER|REMOVE|APPEND|USERDEFINE|SCAN)\\b.*")) {
                    out.add(def(kind, name, body.toString(), line));
                    kind = null;
                } else {
                    body.append(' ').append(l);
                }
            }
        }
        if (kind != null) out.add(def(kind, name, body.toString(), line));
        return out;
    }

    private static Definition def(String kind, String name, String body, int line) {
        Map<String, String> attrs = new LinkedHashMap<>();
        Matcher a = ATTR.matcher(body);
        while (a.find()) attrs.put(a.group(1).toUpperCase(Locale.ROOT), a.group(2).strip());
        return new Definition(kind, name, attrs, line);
    }
}
