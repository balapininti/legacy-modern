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
    private static final Pattern LEVEL = p("^[ \\d]{0,6}[ ]?\\s*(0?[1-9]|[1-4][0-9]|66|77|88)\\s+[A-Z0-9:(][-A-Z0-9:()]*(\\s+|\\.|$)");
    private static final Pattern DECLARE_TABLE = p("EXEC\\s+SQL\\s+DECLARE\\s+\\S+\\s+TABLE");
    private static final Pattern PLI = p("\\bPROC(EDURE)?\\s+OPTIONS\\s*\\(\\s*(MAIN|FETCHABLE)|^\\s*DCL\\s+\\S+.*;\\s*$|^\\s*DECLARE\\s+\\S+.*;\\s*$");
    private static final Pattern ASM = p("^\\S*\\s+(CSECT|DSECT|BALR|LTORG|AMODE|RMODE)(\\s|$)|^\\s+(MACRO|MEND)(\\s|$)|^[A-Z@#$][A-Z0-9@#$]*\\s+(DBD|PCB|PSBGEN)\\s+\\S+=");
    private static final Pattern EASY = p("^\\s*(FILE\\s+\\S+\\s+(FB|VB|VSAM|SEQUENTIAL)|JOB\\s+INPUT\\b)");
    private static final Pattern REXX = p("\\A\\s*(/\\*.*REXX.*\\*/)");
    private static final Pattern CLIST = p("^\\s*(CONTROL\\s+(NO)?(MSG|LIST|PROMPT)|WRITE\\s+&|SET\\s+&)");
    private static final Pattern CONTROL = p("^\\s*(SORT\\s+FIELDS\\s*=|MERGE\\s+FIELDS\\s*=|OPTION\\s+COPY|DEFINE\\s+(CLUSTER|GDG|ALTERNATEINDEX|AIX|PATH)\\b|DELETE\\s+\\S+|REPRO\\b|PSBGEN\\b|DBDGEN\\b|PCB\\s+TYPE|DBD\\s+NAME)");
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
        if (ID_DIV.matcher(code).find()) return new Classification(MemberType.COBOL, dialect(raw), "IDENTIFICATION DIVISION");
        if (BMS.matcher(raw).find()) return of(MemberType.BMS, "DFHMSD/DFHMDI");
        if (CSD.matcher(raw).find()) return of(MemberType.CSD, "CICS DEFINE statements");
        if (REXX.matcher(raw).find()) return of(MemberType.REXX, "/* REXX */ header");
        if (JCL_ANY.matcher(raw).find() && share(m, "//") >= 0.5) return of(MemberType.PROC, "JCL statements with no JOB card");
        if (DDL.matcher(raw).find()) return of(MemberType.DDL, "CREATE statement");
        boolean level = LEVEL.matcher(code).find();
        if ((level || DECLARE_TABLE.matcher(code).find()) && !PROC_DIV.matcher(code).find()) return of(MemberType.COPYBOOK, "level numbers with no PROCEDURE DIVISION");
        if (PLI.matcher(raw).find()) return of(MemberType.PLI, "PL/I signature");
        if (ASM.matcher(raw).find()) return of(MemberType.ASSEMBLER, "Assembler signature");
        if (EASY.matcher(raw).find()) return of(MemberType.EASYTRIEVE, "Easytrieve signature");
        if (CLIST.matcher(raw).find()) return of(MemberType.CLIST, "CLIST signature");
        if (CONTROL.matcher(raw).find()) return of(MemberType.CONTROL, "utility control statements");
        return new Classification(MemberType.UNKNOWN, null, "no known content signature");
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
            if (m.format() == NormalizedMember.Format.FIXED && l.length() > 6 && "*/".indexOf(l.charAt(6)) >= 0) continue;
            if (l.stripLeading().startsWith("*>")) continue;
            out.add(l);
        }
        return out;
    }
}
