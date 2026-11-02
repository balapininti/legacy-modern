package com.modtoolkit.discovery;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** A seeded estate of COBOL, copybooks and JCL with randomized call graphs, for repeatable performance runs. */
public final class SyntheticEstate {
    public record Stats(int programs, int copybooks, int jobs, long lines) {}

    private SyntheticEstate() {}

    public static Stats generate(Path out, long targetLines, long seed) throws IOException {
        Random r = new Random(seed);
        Path cbl = Files.createDirectories(out.resolve("cbl")), cpy = Files.createDirectories(out.resolve("cpy")), jcl = Files.createDirectories(out.resolve("jcl"));
        int avg = 700;
        int programs = (int) Math.max(2, targetLines / avg);
        int copybooks = Math.max(1, programs / 8);
        long lines = 0;
        for (int i = 0; i < copybooks; i++) {
            List<String> l = copybook(i, r);
            Files.write(cpy.resolve(String.format("CB%05d.cpy", i)), l);
            lines += l.size();
        }
        int jobs = 0;
        for (int i = 0; i < programs; i++) {
            List<String> l = program(i, programs, copybooks, r);
            Files.write(cbl.resolve(String.format("PG%05d", i)), l);
            lines += l.size();
            if (i % 5 == 0) {
                List<String> j = job(i, r);
                Files.write(jcl.resolve(String.format("JB%05d.jcl", i)), j);
                lines += j.size();
                jobs++;
            }
        }
        return new Stats(programs, copybooks, jobs, lines);
    }

    private static List<String> copybook(int n, Random r) {
        List<String> l = new ArrayList<>();
        String p = String.format("C%05d", n);
        l.add("      * SYNTHETIC COPYBOOK " + n);
        l.add("       01 " + p + "-REC.");
        int fields = 20 + r.nextInt(60);
        for (int i = 0; i < fields; i++) {
            switch (r.nextInt(6)) {
                case 0 -> l.add(String.format("           05 %s-F%03d PIC X(%d).", p, i, 1 + r.nextInt(40)));
                case 1 -> l.add(String.format("           05 %s-F%03d PIC S9(%d)V99 COMP-3.", p, i, 1 + r.nextInt(11)));
                case 2 -> l.add(String.format("           05 %s-F%03d PIC S9(%d) COMP.", p, i, 1 + r.nextInt(9)));
                case 3 -> {
                    l.add(String.format("           05 %s-F%03d PIC X(2).", p, i));
                    l.add(String.format("              88 %s-F%03d-OK VALUE 'OK'.", p, i));
                }
                case 4 -> {
                    l.add(String.format("           05 %s-F%03d OCCURS %d TIMES.", p, i, 1 + r.nextInt(20)));
                    l.add(String.format("              10 %s-F%03d-A PIC 9(4).", p, i));
                }
                default -> {
                    l.add(String.format("           05 %s-F%03d PIC X(8).", p, i));
                    l.add(String.format("           05 %s-R%03d REDEFINES %s-F%03d PIC 9(8).", p, i, p, i));
                }
            }
        }
        return l;
    }

    private static List<String> program(int n, int programs, int copybooks, Random r) {
        List<String> l = new ArrayList<>();
        String id = String.format("PG%05d", n);
        boolean cics = r.nextInt(4) == 0;
        boolean sql = r.nextInt(3) == 0;
        l.add("       IDENTIFICATION DIVISION.");
        l.add("       PROGRAM-ID. " + id + ".");
        if (!cics) {
            l.add("       ENVIRONMENT DIVISION.");
            l.add("       INPUT-OUTPUT SECTION.");
            l.add("       FILE-CONTROL.");
            l.add("           SELECT INFILE ASSIGN TO INDD.");
        }
        l.add("       DATA DIVISION.");
        if (!cics) {
            l.add("       FILE SECTION.");
            l.add("       FD INFILE.");
            l.add("       01 IN-REC PIC X(80).");
        }
        l.add("       WORKING-STORAGE SECTION.");
        l.add("       01 WS-A PIC X(8).");
        l.add("       01 WS-B PIC S9(7)V99 COMP-3.");
        l.add("       01 WS-C PIC 9(4).");
        l.add("       01 WS-DYN PIC X(8) VALUE '" + String.format("PG%05d", r.nextInt(programs)) + "'.");
        l.add("       01 WS-EOF PIC X VALUE 'N'.");
        for (int c = 0, k = 1 + r.nextInt(4); c < k; c++) l.add("           COPY " + String.format("CB%05d", r.nextInt(copybooks)) + ".");
        if (sql) l.add("           EXEC SQL INCLUDE SQLCA END-EXEC.");
        l.add("       PROCEDURE DIVISION.");
        int paras = 20 + r.nextInt(60);
        l.add("       P0000-MAIN.");
        l.add("           PERFORM P0001 THRU P0001-EXIT.");
        l.add(cics ? "           EXEC CICS RETURN END-EXEC." : "           GOBACK.");
        for (int p = 1; p <= paras; p++) {
            l.add(String.format("       P%04d.", p));
            for (int s = 0, k = 2 + r.nextInt(14); s < k; s++) statement(l, r, programs, cics, sql, p, paras);
            if (p == 1) {
                l.add("       P0001-EXIT.");
                l.add("           EXIT.");
            }
        }
        return l;
    }

    private static void statement(List<String> l, Random r, int programs, boolean cics, boolean sql, int p, int paras) {
        switch (r.nextInt(cics || sql ? 10 : 7)) {
            case 0 -> l.add("           MOVE WS-C TO WS-B.");
            case 1 -> l.add("           COMPUTE WS-B = WS-B * 1.05 + WS-C.");
            case 2 -> {
                l.add("           IF WS-C > " + r.nextInt(100));
                l.add("              IF WS-A = 'X'");
                l.add("                 MOVE 'Y' TO WS-A");
                l.add("              ELSE");
                l.add("                 ADD 1 TO WS-C");
                l.add("              END-IF");
                l.add("           END-IF.");
            }
            case 3 -> l.add(String.format("           CALL 'PG%05d' USING WS-A WS-B.", r.nextInt(programs)));
            case 4 -> l.add("           CALL WS-DYN USING WS-A.");
            case 5 -> {
                if (p < paras) l.add(String.format("           PERFORM P%04d.", p + 1 + r.nextInt(paras - p)));
                else l.add("           CONTINUE.");
            }
            case 6 -> {
                l.add("           EVALUATE WS-C");
                l.add("              WHEN 1 MOVE 'A' TO WS-A");
                l.add("              WHEN 2 MOVE 'B' TO WS-A");
                l.add("              WHEN OTHER CONTINUE");
                l.add("           END-EVALUATE.");
            }
            default -> {
                if (cics) {
                    l.add(String.format("           EXEC CICS LINK PROGRAM('PG%05d')", r.nextInt(programs)));
                    l.add("                COMMAREA(WS-A)");
                    l.add("           END-EXEC.");
                } else if (sql) {
                    l.add("           EXEC SQL");
                    l.add(String.format("                SELECT C1 INTO :WS-A FROM T%03d", r.nextInt(100)));
                    l.add("                WHERE C2 = :WS-C");
                    l.add("           END-EXEC.");
                } else {
                    l.add("           CONTINUE.");
                }
            }
        }
    }

    private static List<String> job(int n, Random r) {
        List<String> l = new ArrayList<>();
        l.add(String.format("//JB%05d JOB (ACCT),'SYNTH',CLASS=A,MSGCLASS=X", n));
        for (int s = 1, k = 1 + r.nextInt(3); s <= k; s++) {
            l.add(String.format("//STEP%02d EXEC PGM=PG%05d", s, n));
            l.add(String.format("//INDD   DD DSN=PROD.DATA.F%05d(0),DISP=SHR", r.nextInt(1000)));
            l.add("//SYSOUT DD SYSOUT=*");
        }
        return l;
    }
}
