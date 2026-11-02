package com.modtoolkit.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pages a result to at most {@link #MAX_CHARS} characters. Whole items where they fit; an item bigger than a page
 * is sent as consecutive pieces of its JSON text, which join back exactly. The cursor is bound to the call, so it
 * cannot be replayed against different arguments.
 */
public final class Paginator {
    public static final int MAX_CHARS = 20_000;
    private static final int ENVELOPE_RESERVE = 400;

    private final ObjectMapper json;
    private final int maxChars;

    public Paginator(ObjectMapper json) {
        this(json, MAX_CHARS);
    }

    Paginator(ObjectMapper json, int maxChars) {
        this.json = json;
        this.maxChars = maxChars;
    }

    public String page(List<?> items, String cursor, String callKey) throws JsonProcessingException {
        int index = 0, offset = 0;
        if (cursor != null && !cursor.isBlank()) {
            String[] c = decode(cursor);
            if (!c[0].equals(callKey)) throw new ToolError("BAD_CURSOR", "this cursor belongs to a different call", "repeat the same tool and arguments, or drop the cursor");
            index = Integer.parseInt(c[1]);
            offset = Integer.parseInt(c[2]);
        }
        int budget = maxChars - ENVELOPE_RESERVE;
        List<Object> page = new ArrayList<>();
        int used = 0;
        int i = index;
        int nextOffset = 0;
        while (i < items.size()) {
            String one = json.writeValueAsString(items.get(i));
            if (offset == 0 && used + one.length() + 1 <= budget) {
                page.add(items.get(i));
                used += one.length() + 1;
                i++;
                continue;
            }
            if (offset == 0 && !page.isEmpty()) break; // Starts fresh on the next page.
            // An oversized item: send the next piece of its text.
            int room = budget - used - 80;
            int end = fit(one, offset, room);
            Map<String, Object> piece = new LinkedHashMap<>();
            piece.put("partial", Map.of("item", i, "offset", offset, "total_chars", one.length(), "last", end >= one.length()));
            piece.put("text", one.substring(offset, end));
            page.add(piece);
            if (end >= one.length()) {
                i++;
                offset = 0;
                nextOffset = 0;
            } else {
                nextOffset = end;
            }
            break;
        }
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("items", page);
        env.put("total_items", items.size());
        boolean more = nextOffset > 0 || i < items.size();
        env.put("next_cursor", more ? encode(callKey, i, nextOffset) : null);
        return json.writeValueAsString(env);
    }

    /** The furthest end such that the piece, once JSON-escaped, still fits. */
    private int fit(String s, int from, int room) throws JsonProcessingException {
        int lo = from + 1, hi = Math.min(s.length(), from + room);
        if (hi <= from) return Math.min(s.length(), from + 1);
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (json.writeValueAsString(s.substring(from, mid)).length() <= room) lo = mid; else hi = mid - 1;
        }
        // Never split a surrogate pair.
        if (lo < s.length() && Character.isHighSurrogate(s.charAt(lo - 1)) && lo - 1 > from) lo--;
        return lo;
    }

    private static String encode(String key, int i, int off) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((key + ":" + i + ":" + off).getBytes(StandardCharsets.UTF_8));
    }

    private static String[] decode(String c) {
        try {
            String[] p = new String(Base64.getUrlDecoder().decode(c), StandardCharsets.UTF_8).split(":");
            if (p.length != 3) throw new IllegalArgumentException();
            Integer.parseInt(p[1]);
            Integer.parseInt(p[2]);
            return p;
        } catch (IllegalArgumentException e) {
            throw new ToolError("BAD_CURSOR", "the cursor is not valid", "use next_cursor exactly as returned");
        }
    }
}
