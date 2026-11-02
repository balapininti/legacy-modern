package com.modtoolkit.parsers.normalize;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/** Detects encoding and format and converts a member to UTF-8 lines. Keeps the hash of the original bytes. */
public final class Normalizer {
    private static final Pattern FREE_DIRECTIVE = Pattern.compile("(?i)>>\\s*SOURCE\\s+(FORMAT\\s+)?(IS\\s+)?FREE");

    private Normalizer() {}

    public static NormalizedMember normalize(byte[] bytes, SourceOptions opts) {
        String sha = sha256(bytes);
        String enc = "auto".equals(opts.encoding()) ? detectEncoding(bytes) : opts.encoding();
        boolean binary = "binary".equals(enc);
        if (binary) {
            return new NormalizedMember("binary", NormalizedMember.Format.FREE, List.of(), sha, true);
        }
        String text = new String(bytes, charset(enc));
        List<String> lines = splitLines(text, enc);
        NormalizedMember.Format fmt = switch (opts.format()) {
            case "fixed" -> NormalizedMember.Format.FIXED;
            case "free" -> NormalizedMember.Format.FREE;
            default -> detectFormat(lines);
        };
        return new NormalizedMember(enc, fmt, lines, sha, false);
    }

    /** Decides by byte statistics: EBCDIC text is dominated by 0x40 blanks and 0xC1-0xF9 alphanumerics. */
    static String detectEncoding(byte[] b) {
        if (b.length == 0) {
            return "utf-8";
        }
        int nul = 0, asciiSpace = 0, asciiPrintable = 0, ebcdicSpace = 0, ebcdicAlnum = 0, ebcdicBracket1047 = 0, ebcdicBracket037 = 0;
        int n = Math.min(b.length, 1 << 20);
        for (int i = 0; i < n; i++) {
            int c = b[i] & 0xFF;
            if (c == 0) nul++;
            if (c == 0x20) asciiSpace++;
            if (c == 0x0A || c == 0x0D || c == 0x09 || (c >= 0x20 && c < 0x7F)) asciiPrintable++;
            if (c == 0x40) ebcdicSpace++;
            if ((c >= 0xC1 && c <= 0xC9) || (c >= 0xD1 && c <= 0xD9) || (c >= 0xE2 && c <= 0xE9) || (c >= 0xF0 && c <= 0xF9)) ebcdicAlnum++;
            if (c == 0xAD || c == 0xBD) ebcdicBracket1047++;
            if (c == 0xBA || c == 0xBB) ebcdicBracket037++;
        }
        double ebcdicShare = (ebcdicSpace + ebcdicAlnum) / (double) n;
        double asciiShare = asciiPrintable / (double) n;
        // EBCDIC first: its blank 0x40 is '@' in ASCII, so blank-heavy EBCDIC otherwise reads as printable ASCII.
        if (ebcdicShare >= 0.60 && ebcdicSpace > asciiSpace) {
            return ebcdicBracket1047 > ebcdicBracket037 ? "cp1047" : "cp037";
        }
        if (asciiShare >= 0.90 && nul == 0) {
            return "utf-8";
        }
        if (nul > 0 || asciiShare < 0.70) {
            return "binary";
        }
        return "utf-8"; // Mostly ASCII with some high bytes: Latin comments in otherwise normal source.
    }

    private static Charset charset(String enc) {
        return switch (enc) {
            case "cp037" -> Charset.forName("IBM037");
            case "cp1047" -> Charset.forName("IBM1047");
            default -> StandardCharsets.UTF_8;
        };
    }

    private static List<String> splitLines(String text, String enc) {
        boolean ebcdic = !"utf-8".equals(enc);
        List<String> out = new ArrayList<>();
        if (ebcdic && text.indexOf('\n') < 0 && text.indexOf('\u0085') < 0) {
            // A dataset dumped as fixed 80-byte records with no line ends.
            int rec = text.length() % 80 == 0 ? 80 : text.length();
            for (int i = 0; rec > 0 && i < text.length(); i += rec) {
                out.add(stripRight(text.substring(i, Math.min(text.length(), i + rec))));
            }
            return out;
        }
        for (String l : text.split("\\r\\n|\\n|\\r|\\u0085", -1)) {
            out.add(stripRight(l));
        }
        if (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    private static String stripRight(String l) {
        int e = l.length();
        while (e > 0 && l.charAt(e - 1) <= ' ') e--;
        return l.substring(0, e);
    }

    private static NormalizedMember.Format detectFormat(List<String> lines) {
        int fixed = 0, total = 0;
        for (String l : lines) {
            if (l.isBlank()) continue;
            if (FREE_DIRECTIVE.matcher(l).find()) return NormalizedMember.Format.FREE;
            total++;
            if (l.length() >= 7 && " *-/dD$".indexOf(l.charAt(6)) >= 0 && l.length() <= 80) fixed++;
            else if (l.length() < 7 && l.substring(0, Math.min(6, l.length())).chars().allMatch(c -> c == ' ' || Character.isDigit(c))) fixed++;
        }
        return total == 0 || fixed >= total * 0.8 ? NormalizedMember.Format.FIXED : NormalizedMember.Format.FREE;
    }

    public static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
