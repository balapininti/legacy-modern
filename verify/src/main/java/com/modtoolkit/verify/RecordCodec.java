package com.modtoolkit.verify;

import com.modtoolkit.parsers.copybook.CopybookLayout;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bytes to fields and back. Numbers come out as BigDecimal, so no precision is lost on the way to a fixture. Zoned signs
 * are read in the IBM form both as true EBCDIC and as the ASCII overpunch letters that exports carry.
 */
public final class RecordCodec {
    private static final String POS = "{ABCDEFGHI", NEG = "}JKLMNOPQR";

    private final Charset charset;
    private final boolean ebcdic;

    public RecordCodec(String encoding) {
        this.ebcdic = encoding.equalsIgnoreCase("cp037") || encoding.equalsIgnoreCase("cp1047");
        this.charset = Charset.forName(ebcdic ? (encoding.equalsIgnoreCase("cp037") ? "IBM037" : "IBM1047") : "ISO-8859-1");
    }

    /** Field name to value; named fields only, repeated ones as lists. Every REDEFINES view is decoded. */
    public Map<String, Object> decode(byte[] rec, RecordLayout layout) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<RecordLayout.Field> fields = layout.fields();
        for (int i = 0; i < fields.size(); i++) {
            RecordLayout.Field f = fields.get(i);
            if (f.parent() >= 0 || f.name() == null && kids(fields, i).isEmpty()) continue;
            decodeInto(out, rec, fields, i, 0);
        }
        return out;
    }

    private void decodeInto(Map<String, Object> out, byte[] rec, List<RecordLayout.Field> fields, int i, int base) {
        RecordLayout.Field f = fields.get(i);
        int count = Math.max(1, f.occurs());
        // OCCURS DEPENDING ON: only the live occurrences are data; the rest of the table is whatever was left there.
        if (f.odoRef() != null) {
            Object n = out.get(f.odoRef().toUpperCase(Locale.ROOT));
            if (n instanceof BigDecimal b) count = Math.min(count, b.intValue());
        }
        List<Object> repeated = f.occurs() > 1 || f.odoRef() != null ? new ArrayList<>() : null;
        for (int k = 0; k < count; k++) {
            int shift = base + k * f.length();
            Object v;
            if (f.group()) {
                Map<String, Object> g = new LinkedHashMap<>();
                for (int c : kids(fields, i)) decodeInto(g, rec, fields, c, shift);
                v = g;
            } else {
                v = f.name() == null ? null : elementary(rec, f, f.offset() + shift);
            }
            if (repeated != null) repeated.add(v);
            else if (f.group() && f.name() == null) out.putAll(castMap(v));
            else if (f.group()) out.putAll(flatten(f.name(), castMap(v)));
            else if (f.name() != null) out.put(f.name(), v);
        }
        if (repeated != null && f.name() != null) out.put(f.name(), repeated);
    }

    /** Group fields stay flat under their own names, as the copybook guarantees they are unique in a record. */
    private static Map<String, Object> flatten(String name, Map<String, Object> m) {
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object v) {
        return (Map<String, Object>) v;
    }

    private static List<Integer> kids(List<RecordLayout.Field> fields, int parent) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < fields.size(); i++) if (fields.get(i).parent() == parent) out.add(i);
        return out;
    }

    private Object elementary(byte[] rec, RecordLayout.Field f, int off) {
        if (off + f.length() > rec.length) return null; // A short variable-length record: this field is not there.
        String usage = f.usage() == null ? "DISPLAY" : f.usage().toUpperCase(Locale.ROOT);
        String pic = f.pic() == null ? "" : f.pic().toUpperCase(Locale.ROOT);
        return switch (usage) {
            case "COMP-3", "COMPUTATIONAL-3", "PACKED-DECIMAL" -> packed(rec, off, f.length(), scale(pic));
            case "COMP", "COMPUTATIONAL", "COMP-4", "COMPUTATIONAL-4", "COMP-5", "COMPUTATIONAL-5", "BINARY" -> binary(rec, off, f.length(), pic.startsWith("S"), scale(pic));
            default -> pic.matches("S?[9VP()0-9]+") && !pic.isEmpty() ? zoned(rec, off, f.length(), pic.startsWith("S"), f.signSeparate(), scale(pic)) : text(rec, off, f.length());
        };
    }

    private String text(byte[] rec, int off, int len) {
        return new String(rec, off, len, charset).replace('\u0000', ' ');
    }

    static BigDecimal packed(byte[] b, int off, int len, int scale) {
        BigInteger v = BigInteger.ZERO;
        for (int i = 0; i < len; i++) {
            int x = b[off + i] & 0xFF;
            v = v.multiply(BigInteger.TEN).add(BigInteger.valueOf(x >> 4));
            if (i < len - 1) v = v.multiply(BigInteger.TEN).add(BigInteger.valueOf(x & 0xF));
        }
        int sign = b[off + len - 1] & 0xF;
        return new BigDecimal(sign == 0xD || sign == 0xB ? v.negate() : v, scale);
    }

    static BigDecimal binary(byte[] b, int off, int len, boolean signed, int scale) {
        byte[] raw = new byte[len];
        System.arraycopy(b, off, raw, 0, len);
        return new BigDecimal(signed ? new BigInteger(raw) : new BigInteger(1, raw), scale);
    }

    private BigDecimal zoned(byte[] b, int off, int len, boolean signed, boolean separate, int scale) {
        StringBuilder digits = new StringBuilder();
        boolean neg = false;
        for (int i = 0; i < len; i++) {
            int x = b[off + i] & 0xFF;
            if (ebcdic) {
                if (i == len - 1 && signed && (x >> 4) == 0xD) neg = true;
                digits.append((char) ('0' + (x & 0xF)));
            } else {
                char c = (char) x;
                // A sign lives in the last byte; a letter anywhere else is junk, not a digit.
                boolean last = i == len - 1;
                int p = last ? POS.indexOf(c) : -1, q = last ? NEG.indexOf(c) : -1;
                if (p >= 0) digits.append((char) ('0' + p));
                else if (q >= 0) {
                    digits.append((char) ('0' + q));
                    neg = true;
                } else if (last && c >= 'p' && c <= 'y') {
                    digits.append((char) ('0' + (c - 'p'))); // GnuCOBOL's native ASCII negative zone.
                    neg = true;
                } else if (c == ' ') digits.append('0');
                else if (Character.isDigit(c)) digits.append(c);
                else if (c == '-') neg = true;
                else if (c != '+') return null; // Not a number in a numeric field: real data has these, and a fixture must show it.
            }
        }
        BigInteger v = new BigInteger(digits.isEmpty() ? "0" : digits.toString());
        return new BigDecimal(neg ? v.negate() : v, scale);
    }

    /** Digits after V. */
    static int scale(String pic) {
        int v = pic.indexOf('V');
        if (v < 0) return 0;
        String tail = pic.substring(v + 1);
        int n = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("9\\((\\d+)\\)|9").matcher(tail);
        while (m.find()) n += m.group(1) == null ? 1 : Integer.parseInt(m.group(1));
        return n;
    }

    /** For tests and for building input records: the reverse of decode for elementary fields. */
    public byte[] encode(Map<String, Object> values, RecordLayout layout) {
        byte[] rec = new byte[layout.length()];
        java.util.Arrays.fill(rec, (byte) (ebcdic ? 0x40 : 0x20));
        for (RecordLayout.Field f : layout.fields()) {
            if (f.group() || f.name() == null || !values.containsKey(f.name())) continue;
            writeField(rec, f, f.offset(), values.get(f.name()));
        }
        return rec;
    }

    private void writeField(byte[] rec, RecordLayout.Field f, int off, Object v) {
        String usage = f.usage() == null ? "DISPLAY" : f.usage().toUpperCase(Locale.ROOT);
        String pic = f.pic() == null ? "" : f.pic().toUpperCase(Locale.ROOT);
        if (usage.matches("COMP-3|COMPUTATIONAL-3|PACKED-DECIMAL")) {
            BigInteger u = ((BigDecimal) v).movePointRight(scale(pic)).toBigIntegerExact();
            String d = u.abs().toString();
            int nibbles = f.length() * 2 - 1;
            d = "0".repeat(Math.max(0, nibbles - d.length())) + d;
            for (int i = 0; i < f.length(); i++) {
                int hi = d.charAt(i * 2) - '0';
                int lo = i < f.length() - 1 ? d.charAt(i * 2 + 1) - '0' : (u.signum() < 0 ? 0xD : pic.startsWith("S") ? 0xC : 0xF);
                rec[off + i] = (byte) ((hi << 4) | lo);
            }
        } else if (usage.matches("COMP|COMPUTATIONAL|COMP-4|COMPUTATIONAL-4|COMP-5|COMPUTATIONAL-5|BINARY")) {
            byte[] raw = ((BigDecimal) v).movePointRight(scale(pic)).toBigIntegerExact().toByteArray();
            byte fill = (byte) (((BigDecimal) v).signum() < 0 ? 0xFF : 0);
            for (int i = 0; i < f.length(); i++) rec[off + f.length() - 1 - i] = i < raw.length ? raw[raw.length - 1 - i] : fill;
        } else if (v instanceof BigDecimal n) {
            BigInteger u = n.movePointRight(scale(pic)).toBigIntegerExact();
            String d = u.abs().toString();
            d = "0".repeat(Math.max(0, f.length() - d.length())) + d;
            byte[] out = new byte[f.length()];
            for (int i = 0; i < f.length(); i++) {
                int dg = d.charAt(i) - '0';
                boolean last = i == f.length() - 1;
                if (ebcdic) out[i] = (byte) (((last && pic.startsWith("S") ? (u.signum() < 0 ? 0xD : 0xC) : 0xF) << 4) | dg);
                else out[i] = (byte) (last && pic.startsWith("S") ? (u.signum() < 0 ? NEG : POS).charAt(dg) : '0' + dg);
            }
            System.arraycopy(out, 0, rec, off, f.length());
        } else {
            byte[] t = String.valueOf(v).getBytes(charset);
            System.arraycopy(t, 0, rec, off, Math.min(t.length, f.length()));
        }
    }

    public static int width(String pic, String usage) {
        return CopybookLayout.compute(List.of(new CopybookLayout.Item(1, "X", pic, usage, false, 1, null))).get(0).length();
    }
}
