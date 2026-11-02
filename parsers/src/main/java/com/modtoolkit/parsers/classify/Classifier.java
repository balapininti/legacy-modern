package com.modtoolkit.parsers.classify;

import com.modtoolkit.parsers.normalize.NormalizedMember;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Classification by content signature only; names and extensions are never consulted. */
public final class Classifier {
    private static Pattern p(String r) { return Pattern.compile(r, Pattern.CASE_INSENSITIVE | Pattern.MULTILINE); }
    private static final Pattern ID_DIV = p("^.{0,12}\\b(IDENTIFICATION|ID)\\s+DIVISION\\s*\\.");
    private static final Pattern PROC_DIV = p("\\bPROCEDURE\\s+DIVISION\\b");
    private static final Pattern JCL_JOB = p("^//[A-Z0-9@#$]{0,8}\\s+JOB(\\s|$)");
    private static final Pattern JCL_PROC = p("^//[A-Z0-9@#$]{0,8}\\s+PROC(\\s|$)|^//[A-Z0-9@#$]*\\s+PEND(\\s|$)");
    private static final Pattern JCL_ANY = p("^//[A-Z0-9@#$]*\\s+(EXEC|DD|SET|INCLUDE|IF|ENDIF|JCLLIB)(\\s|$)");
    private static final Pattern BMS = p("\\bDFHMSD\\b|\\bDFHMDI\\b");
    private static final Pattern DDL = p("^\\s*CREATE\\s+((UNIQUE\\s+)?INDEX|TABLE|VIEW|TABLESPACE|DATABASE|SYNONYM|ALIAS|STOGROUP)\\b");
    private static final Pattern CSD = p("^\\s*DEFINE\\s+(TRANSACTION|PROGRAM|MAPSET|FILE|TDQUEUE|TSMODEL|LIBRARY|DB2CONN|DB2ENTRY)\\s*\\(");
    /** One data description: a level, one name, then clauses or a period. Words after the name rule out prose lists. */
    private static final Pattern LEVEL = Pattern.compile("^\\s*(0?[1-9]|[1-4][0-9]|66|77|88)\\s+(\\S+?)(\\s+(PIC|PICTURE|REDEFINES|OCCURS|COMP\\S*|BINARY|USAGE|VALUES?|PACKED-DECIMAL|DISPLAY|SIGN|JUST\\S*|BLANK|INDEXED|POINTER|RENAMES|EXTERNAL|GLOBAL|SYNC\\S*)\\b.*|\\s*\\.\\s*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DECLARE_TABLE = p("EXEC\\s+SQL\\s+DECLARE\\s+\\S+\\s+TABLE");
    private static final Pattern PLI = p("\\bPROC(EDURE)?\\s+OPTIONS\\s*\\(\\s*(MAIN|FETCHABLE)|^\\s*DCL\\s+\\S+|^\\s*DECLARE\\s+\\S+.*[;,]\\s*$|^\\s*\\S+\\s*:\\s*PROC\\b|^\\s*%\\s*INCLUDE\\b|^\\s*/\\*");
    private static final Pattern FIRST_CODE_SKIP = p("^\\s*(CBL\\b|PROCESS\\b|\\$SET\\b|>>|\\*>|EJECT\\b|SKIP[123]\\b|TITLE\\b).*$");
    private static final Pattern ASM = p("^\\S*\\s+(CSECT|DSECT|BALR|LTORG|AMODE|RMODE)(\\s|$)|^\\s+(MACRO|MEND)(\\s|$)|^[A-Z@#$][A-Z0-9@#$]*\\s+(DBD|PCB|PSBGEN)\\s+\\S+=");
    private static final Pattern EASY = p("^\\s*(FILE\\s+\\S+\\s+(FB|VB|VSAM|SEQUENTIAL)|JOB\\s+INPUT\\b)");
    private static final Pattern REXX = p("\\A\\s*(/\\*.*REXX.*\\*/)");
    private static final Pattern CLIST = p("^\\s*(CONTROL\\s+(NO)?(MSG|LIST|PROMPT)|WRITE\\s+&|SET\\s+&)");
    private static final Pattern CONTROL = p("^\\s*(SORT\\s+FIELDS\\s*=|MERGE\\s+FIELDS\\s*=|OPTION\\s+COPY|DEFINE\\s+(CLUSTER|GDG|ALTERNATEINDEX|AIX|PATH)\\b|DELETE\\s+\\S+|REPRO\\b|PSBGEN\\b|DBDGEN\\b|PCB\\s+TYPE|DBD\\s+NAME|DROP\\s+(TABLE|INDEX|VIEW|TABLESPACE|DATABASE)\\b|GRANT\\s|SET\\s+CURRENT\\s|FREE\\s+PLAN\\b|DSN\\s+SYSTEM\\b|LOAD\\s+DATA\\b|BIND\\s+(PLAN|PACKAGE)\\b)");
    private static final Pattern SQL_CONTROL = p("^\\s*(DROP\\s+(TABLE|INDEX|VIEW|TABLESPACE|DATABASE)\\b|GRANT\\s|SET\\s+CURRENT\\s|FREE\\s+PLAN\\b|DSN\\s+SYSTEM\\b|LOAD\\s+DATA\\b|BIND\\s+(PLAN|PACKAGE)\\b)|\\bFIELDS\\s*=\\s*\\(|\\bREPRO\\s+IFILE\\b|^\\s*RUN\\s+PROGRAM\\s*\\(");
    private static final Pattern MF = p("^\\s*\\$SET\\b");
    private static final Pattern GNU = p(">>\\s*SOURCE|^\\s*>>");

    private Classifier() {}

    public static Classification classify(NormalizedMember m) {
        if (m.binary()) return new Classification(MemberType.UNKNOWN, null, "binary content");
        if (m.lines().stream().allMatch(String::isBlank)) return new Classification(MemberType.UNKNOWN, null, "empty");
        String raw = String.join("\n", m.lines());
        // COBOL code with comments removed, so a comment mentioning a keyword never decides the type.
        String code = String.join("\n", codeLines(m));
        if (JCL_JOB.matcher(raw).find()) return of(MemberType.JCL, "//... JOB");
        if (JCL_PROC.matcher(raw).find()) return of(MemberType.PROC, "//... PROC or PEND");
        // First statement, not anywhere: a tutorial quoting COBOL in a code block is not a program.
        if (firstCodeIsIdDivision(code)) return new Classification(MemberType.COBOL, dialect(raw), "IDENTIFICATION DIVISION");
        if (BMS.matcher(raw).find()) return of(MemberType.BMS, "DFHMSD/DFHMDI");
        if (CSD.matcher(raw).find()) return of(MemberType.CSD, "CICS DEFINE statements");
        if (REXX.matcher(raw).find()) return of(MemberType.REXX, "/* REXX */ header");
        if (JCL_ANY.matcher(raw).find() && share(m, "//") >= 0.5) return of(MemberType.PROC, "JCL statements with no JOB card");
        if (DDL.matcher(raw).find()) return of(MemberType.DDL, "CREATE statement");
        if (SQL_CONTROL.matcher(raw).find() && !raw.toUpperCase(java.util.Locale.ROOT).contains("DCL ")) return of(MemberType.CONTROL, "utility control statements");
        double levels = levelShare(code);
        // PL/I structures have levels too, so PL/I is decided by its statements: they end in semicolons.
        if (PLI.matcher(raw).find() && semicolonShare(raw) >= 0.15 && levels < 0.5 && !isProse(raw)) return of(MemberType.PLI, "PL/I signature");
        if ((levels >= 0.25 || DECLARE_TABLE.matcher(code).find()) && !PROC_DIV.matcher(code).find()) return of(MemberType.COPYBOOK, "level numbers with no PROCEDURE DIVISION");
        if (PLI.matcher(raw).find() && raw.contains("/*") && semicolonShare(raw) > 0 && !isProse(raw)) return of(MemberType.PLI, "PL/I signature");
        if (ASM.matcher(raw).find()) return of(MemberType.ASSEMBLER, "Assembler signature");
        if (EASY.matcher(raw).find()) return of(MemberType.EASYTRIEVE, "Easytrieve signature");
        if (CLIST.matcher(raw).find()) return of(MemberType.CLIST, "CLIST signature");
        if (CONTROL.matcher(raw).find()) return of(MemberType.CONTROL, "utility control statements");
        return new Classification(MemberType.UNKNOWN, null, "no known content signature");
    }

    private static boolean firstCodeIsIdDivision(String code) {
        for (String l : code.split("\n")) {
            // Blank by hand: in MULTILINE mode ^ does not match an empty string, so the pattern alone would miss it.
            if (l.isBlank() || FIRST_CODE_SKIP.matcher(l).matches()) continue;
            return ID_DIV.matcher(l).find();
        }
        return false;
    }

    private static double levelShare(String code) {
        int total = 0, hits = 0;
        for (String l : code.split("\n")) {
            if (l.isBlank()) continue;
            total++;
            if (LEVEL.matcher(l.length() > 6 && l.startsWith("      ") ? l.substring(6) : l).matches()) hits++;
        }
        return total == 0 ? 0 : hits / (double) total;
    }

    /** Markdown and other documents: headings and links, or mostly lower-case running text. Source is not written so. */
    private static boolean isProse(String raw) {
        int total = 0, prose = 0;
        for (String l : raw.split("\n")) {
            if (l.isBlank()) continue;
            total++;
            String t = l.strip();
            if (t.startsWith("# ") || t.startsWith("## ") || t.startsWith("- [") || t.startsWith("| ") || t.contains("](http")) prose++;
            else if (t.split("\\s+").length >= 8 && !t.endsWith(";") && !t.startsWith("/*")) {
                long lower = t.chars().filter(Character::isLowerCase).count(), upper = t.chars().filter(Character::isUpperCase).count();
                if (lower > upper * 3) prose++;
            }
        }
        return total > 0 && prose >= total * 0.2;
    }

    private static double semicolonShare(String raw) {
        int total = 0, hits = 0;
        for (String l : raw.split("\n")) {
            if (l.isBlank()) continue;
            total++;
            String c = l.length() > 72 ? l.substring(0, 72) : l;
            if (c.stripTrailing().endsWith(";") || c.contains("*/")) hits++;
        }
        return total == 0 ? 0 : hits / (double) total;
    }

    private static Classification of(MemberType t, String why) { return new Classification(t, null, why); }

    private static String dialect(String raw) {
        if (MF.matcher(raw).find()) return "microfocus";
        if (GNU.matcher(raw).find()) return "gnucobol";
        return "ibm-enterprise";
    }

    private static double share(NormalizedMember m, String prefix) {
        long nb = m.lines().stream().filter(l -> !l.isBlank()).count();
        return nb == 0 ? 0 : m.lines().stream().filter(l -> l.startsWith(prefix)).count() / (double) nb;
    }

    private static List<String> codeLines(NormalizedMember m) {
        List<String> out = new ArrayList<>();
        for (String l : m.cobolLines()) {
            if (m.format() == NormalizedMember.Format.FIXED && l.length() > 6 && "*/$".indexOf(l.charAt(6)) >= 0) continue;
            String t = l.stripLeading();
            if (t.startsWith("*>") || (m.format() == NormalizedMember.Format.FREE && t.startsWith("*"))) continue;
            out.add(l);
        }
        return out;
    }
}
