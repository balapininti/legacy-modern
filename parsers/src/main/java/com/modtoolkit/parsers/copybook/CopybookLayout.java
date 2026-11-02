package com.modtoolkit.parsers.copybook;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Byte offsets and lengths for a record, using IBM Enterprise COBOL sizing rules. Replaces cb2xml; see ADR 0002. */
public final class CopybookLayout {
    /** Input description of one item, in source order. Level 88 and 66 items are not passed in. */
    public record Item(int level, String name, String pic, String usage, boolean signSeparate, int occursMax, String redefines) {}

    /** {@code length} is one occurrence; {@code totalLength} includes OCCURS. ODO tables are sized at their maximum. */
    public record FieldLayout(Item item, int parent, int offset, int length, int totalLength) {}

    private static final Pattern REPEAT = Pattern.compile("(.)\\((\\d+)\\)");

    private CopybookLayout() {}

    public static List<FieldLayout> compute(List<Item> items) {
        int n = items.size();
        int[] parent = new int[n];
        List<List<Integer>> kids = new ArrayList<>();
        for (int i = 0; i < n; i++) kids.add(new ArrayList<>());
        List<Integer> roots = new ArrayList<>();
        int[] stack = new int[64];
        int top = -1;
        for (int i = 0; i < n; i++) {
            int lvl = norm(items.get(i).level());
            while (top >= 0 && norm(items.get(stack[top]).level()) >= lvl) top--;
            parent[i] = top >= 0 ? stack[top] : -1;
            if (top >= 0) kids.get(stack[top]).add(i); else roots.add(i);
            stack[++top] = i;
        }
        int[] len = new int[n];
        int[] off = new int[n];
        for (int r : roots) size(r, items, kids, len, null);
        for (int r : roots) place(r, 0, items, kids, len, off, new HashMap<>());
        List<FieldLayout> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new FieldLayout(items.get(i), parent[i], off[i], len[i], Math.multiplyExact(len[i], Math.max(1, items.get(i).occursMax()))));
        }
        return out;
    }

    private static int norm(int level) {
        return level == 77 ? 1 : level;
    }

    private static int size(int i, List<Item> items, List<List<Integer>> kids, int[] len, String inheritedUsage) {
        Item it = items.get(i);
        String usage = it.usage() != null ? it.usage() : inheritedUsage;
        if (kids.get(i).isEmpty()) {
            len[i] = elementaryLength(it.pic(), usage, it.signSeparate());
        } else {
            int total = 0;
            for (int k : kids.get(i)) {
                int childTotal = size(k, items, kids, len, usage);
                if (items.get(k).redefines() == null) total += childTotal;
            }
            len[i] = total;
        }
        return len[i] * Math.max(1, it.occursMax());
    }

    private static void place(int i, int at, List<Item> items, List<List<Integer>> kids, int[] len, int[] off, Map<String, Integer> seen) {
        off[i] = at;
        int cur = at;
        Map<String, Integer> names = new HashMap<>();
        for (int k : kids.get(i)) {
            Item c = items.get(k);
            int where = cur;
            if (c.redefines() != null) {
                where = names.getOrDefault(c.redefines().toUpperCase(Locale.ROOT), cur);
            }
            place(k, where, items, kids, len, off, seen);
            if (c.name() != null) names.put(c.name().toUpperCase(Locale.ROOT), where);
            if (c.redefines() == null) cur = where + len[k] * Math.max(1, c.occursMax());
        }
    }

    /** Display positions in a PICTURE: S, V and P hold no storage; CR and DB take two. */
    public static int displayPositions(String pic) {
        return (int) picStats(pic)[0];
    }

    /** Digit count of a numeric PICTURE, for the binary and packed widths. */
    public static int digits(String pic) {
        return (int) picStats(pic)[1];
    }

    private static long[] picStats(String picRaw) {
        String p = expand(picRaw.toUpperCase(Locale.ROOT));
        p = p.replace("CR", "##").replace("DB", "##");
        long positions = 0, digits = 0;
        for (char c : p.toCharArray()) {
            switch (c) {
                case 'S', 'V' -> {}
                case 'P' -> digits++;
                case '9' -> { positions++; digits++; }
                default -> positions++;
            }
        }
        return new long[] {positions, digits};
    }

    private static String expand(String pic) {
        Matcher m = REPEAT.matcher(pic);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(pic, last, m.start());
            int cnt = new BigInteger(m.group(2)).min(BigInteger.valueOf(200_000_000)).intValue();
            sb.append(m.group(1).repeat(cnt));
            last = m.end();
        }
        sb.append(pic.substring(last));
        return sb.toString();
    }

    static int elementaryLength(String pic, String usage, boolean signSeparate) {
        String u = usage == null ? "DISPLAY" : usage.toUpperCase(Locale.ROOT);
        switch (u) {
            case "COMP-1", "COMPUTATIONAL-1", "INDEX", "POINTER", "PROCEDURE-POINTER" -> { return "PROCEDURE-POINTER".equals(u) ? 8 : 4; }
            case "COMP-2", "COMPUTATIONAL-2", "FUNCTION-POINTER" -> { return 8; }
            default -> {}
        }
        if (pic == null) return 0;
        String p = pic.toUpperCase(Locale.ROOT);
        switch (u) {
            case "COMP", "COMPUTATIONAL", "COMP-4", "COMPUTATIONAL-4", "COMP-5", "COMPUTATIONAL-5", "BINARY" -> {
                int d = digits(p);
                return d <= 4 ? 2 : d <= 9 ? 4 : 8;
            }
            case "COMP-3", "COMPUTATIONAL-3", "PACKED-DECIMAL" -> { return digits(p) / 2 + 1; }
            default -> {}
        }
        int positions = displayPositions(p);
        if (p.contains("N") || p.contains("G") || u.equals("NATIONAL") || u.equals("DISPLAY-1")) positions *= 2;
        return positions + (signSeparate ? 1 : 0);
    }
}
