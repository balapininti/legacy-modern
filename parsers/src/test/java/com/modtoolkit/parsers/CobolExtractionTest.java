package com.modtoolkit.parsers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.modtoolkit.parsers.cobol.*;
import com.modtoolkit.parsers.cobol.CobolFacts.*;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** One fixture per hard case in Module 1. Sources are fixed format with the first six columns blank. */
class CobolExtractionTest {
    private final CopybookLibrary lib = new CopybookLibrary();

    private static List<String> lines(String s) {
        return Arrays.asList(s.split("\n", -1));
    }

    private void copy(String name, String body) {
        lib.add(name, "cpy/" + name + ".cpy", lines(body));
    }

    private CobolFacts parse(String src) {
        return new ProLeapCobolParser().parse(CopybookResolver.expand("MAIN", lines(src), lib, false));
    }

    private ProgramFacts one(String src) {
        CobolFacts f = parse(src);
        assertThat(f.programs).hasSize(1);
        return f.programs.get(0);
    }

    private static final String HEAD = """
       IDENTIFICATION DIVISION.
       PROGRAM-ID. MAIN.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01 WS-A PIC X(8).
""";

    @Test
    void paragraphsAndSectionsWithExactLines() {
        ProgramFacts p = one(HEAD + """
       PROCEDURE DIVISION.
       MAIN-SEC SECTION.
       1000-START.
           PERFORM 2000-WORK THRU 2000-EXIT.
           GOBACK.
       2000-WORK.
           DISPLAY 'X'.
       2000-EXIT.
           EXIT.
       9999-DEAD.
           DISPLAY 'NEVER'.
""");
        assertThat(p.programId).isEqualTo("MAIN");
        Para start = p.paragraphs.stream().filter(x -> x.name().equals("1000-START")).findFirst().orElseThrow();
        assertThat(start.start().line()).isEqualTo(8);
        assertThat(start.lineEnd()).isEqualTo(10);
        assertThat(start.section()).isEqualTo("MAIN-SEC");
        assertThat(p.performs).extracting(Perform::target, Perform::thru).containsExactly(org.assertj.core.groups.Tuple.tuple("2000-WORK", "2000-EXIT"));
        assertThat(p.performs.get(0).loc().line()).isEqualTo(9);
        assertThat(p.stops).extracting(Stop::verb).containsExactly("GOBACK");
    }

    @Test
    void neverReachedParagraphIsFlaggedAndReachedOnesAreNot() {
        ProgramFacts p = one(HEAD + """
       PROCEDURE DIVISION.
       A000.
           PERFORM B000.
           GOBACK.
       B000.
           DISPLAY 'X'.
       C000.
           GO TO D000.
       D000.
           DISPLAY 'Y'.
           GOBACK.
       E000.
           DISPLAY 'NEVER'.
""");
        // B000 falls through to C000 and on to D000; E000 follows a GOBACK and nothing targets it.
        assertThat(p.unreachable).containsExactly("E000");
    }

    @Test
    void continuationLineKeepsLaterLineNumbersExact() {
        ProgramFacts p = one("""
       IDENTIFICATION DIVISION.
       PROGRAM-ID. MAIN.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01 A PIC X(80) VALUE 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
      -    'BBBB'.
       PROCEDURE DIVISION.
       P1.
           CALL 'SUB1'.
           GOBACK.
""");
        assertThat(p.calls.get(0).loc().line()).isEqualTo(9);
        assertThat(p.paragraphs.get(0).start().line()).isEqualTo(8);
        assertThat(p.dataItems.get(0).values().get(0)).endsWith("ABBBB").hasSize(52 + 4 - 1 + 0);
    }

    @Test
    void debuggingLinesAreCommentsUnlessDebuggingModeIsOn() {
        String body = """
       PROCEDURE DIVISION.
      D    CALL 'DBGSUB'.
           GOBACK.
""";
        assertThat(one(HEAD + body).calls).isEmpty();
        ProgramFacts on = one("""
       IDENTIFICATION DIVISION.
       PROGRAM-ID. MAIN.
       ENVIRONMENT DIVISION.
       CONFIGURATION SECTION.
       SOURCE-COMPUTER. IBM-370 WITH DEBUGGING MODE.
""" + body);
        assertThat(on.calls).extracting(Call::target).containsExactly("DBGSUB");
    }

    @Test
    void staticDynamicAndUnresolvedCalls() {
        ProgramFacts p = one("""
       IDENTIFICATION DIVISION.
       PROGRAM-ID. MAIN.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01 WS-VAL PIC X(8) VALUE 'VALSUB'.
       01 WS-MOV PIC X(8).
       01 WS-UNK PIC X(8).
       PROCEDURE DIVISION.
           CALL 'STAT1' USING WS-VAL.
           CALL WS-VAL.
           MOVE 'MOVSUB' TO WS-MOV.
           CALL WS-MOV.
           CANCEL WS-MOV.
           CALL WS-UNK.
           GOBACK.
""");
        assertThat(p.calls).extracting(Call::target, Call::kind).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("STAT1", CallKind.STATIC),
                org.assertj.core.groups.Tuple.tuple("VALSUB", CallKind.DYNAMIC_RESOLVED),
                org.assertj.core.groups.Tuple.tuple("MOVSUB", CallKind.DYNAMIC_RESOLVED),
                org.assertj.core.groups.Tuple.tuple("WS-UNK", CallKind.DYNAMIC_UNRESOLVED));
        assertThat(p.calls.stream().filter(c -> c.target().equals("STAT1")).findFirst().orElseThrow().using()).containsExactly("WS-VAL");
        assertThat(p.cancels).extracting(Cancel::target).containsExactly("WS-MOV");
    }

    /** What ProLeap rejects, the fallback takes, flagged, with the same facts in the same places. */
    @Test
    void koopaFallbackParsesWhatProLeapRejectsAndSaysSo() {
        String src = """
       IDENTIFICATION DIVISION.
       PROGRAM-ID. MAIN.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01 REC.
          05 SEARCH PIC X(8).
          05 AMT PIC S9(7)V99 COMP-3.
          05 WS-DYN PIC X(8) VALUE 'DYNPGM'.
          05 TBL OCCURS 3 TIMES.
             10 ELT PIC X(2).
       LINKAGE SECTION.
       01 LS-IN PIC X.
       PROCEDURE DIVISION USING LS-IN.
       P1.
           IF AMT > 0
              CALL 'STAT1' USING AMT
           END-IF.
           CALL WS-DYN.
           EXEC SQL SELECT C1 INTO :AMT FROM T1 END-EXEC.
           EXEC CICS LINK PROGRAM('LNK1') END-EXEC.
           MOVE AMT TO ELT(1).
           PERFORM P2 THRU P3.
           GO TO P3.
       P2.
           DISPLAY 'X'.
       P3.
           GOBACK.
       P4.
           DISPLAY 'NEVER'.
""";
        ExpandedSource e = CopybookResolver.expand("MAIN", lines(src), lib, false);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ProLeapCobolParser().parse(e)).isInstanceOf(CobolParseException.class);
        var o = new ResilientParser(List.of(new ProLeapCobolParser(), new KoopaCobolParser()), Duration.ofMinutes(1)).parse(e);
        assertThat(o.ok()).as(String.valueOf(o.error())).isTrue();
        assertThat(o.parser()).isEqualTo("koopa");
        assertThat(o.rejectedBy()).hasSize(1);
        CobolFacts f = o.facts();
        assertThat(f.features).contains("FALLBACK PARSER koopa", "EXEC SQL", "EXEC CICS", "COMP-3");
        ProgramFacts p = f.programs.get(0);
        assertThat(p.programId).isEqualTo("MAIN");
        assertThat(p.calls).extracting(Call::target, Call::kind).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("STAT1", CallKind.STATIC), org.assertj.core.groups.Tuple.tuple("DYNPGM", CallKind.DYNAMIC_RESOLVED));
        assertThat(p.calls.stream().filter(c -> c.target().equals("STAT1")).findFirst().orElseThrow().loc().line()).isEqualTo(16);
        assertThat(p.calls.stream().filter(c -> c.target().equals("STAT1")).findFirst().orElseThrow().using()).containsExactly("AMT");
        assertThat(p.usingList).containsExactly("LS-IN");
        assertThat(p.sql).extracting(Sql::tables).containsExactly(List.of("T1"));
        assertThat(p.cics).extracting(Cics::resourceName).containsExactly("LNK1");
        assertThat(p.paragraphs).extracting(Para::name).containsExactly("P1", "P2", "P3", "P4");
        assertThat(p.paragraphs.get(0).start().line()).isEqualTo(14);
        assertThat(p.performs).extracting(Perform::target, Perform::thru).containsExactly(org.assertj.core.groups.Tuple.tuple("P2", "P3"));
        assertThat(p.gotos).extracting(GoTo::target).containsExactly("P3");
        assertThat(p.moves).extracting(Move::from, Move::to).containsExactly(org.assertj.core.groups.Tuple.tuple("AMT", "ELT"));
        assertThat(p.unreachable).containsExactly("P4");
        DataItem amt = p.dataItems.stream().filter(d -> "AMT".equals(d.name())).findFirst().orElseThrow();
        assertThat(amt.usage()).isEqualTo("COMP-3");
        assertThat(amt.length()).isEqualTo(5);
        assertThat(amt.offset()).isEqualTo(8);
    }

    /** Each of these is valid COBOL that a real repository uses and the stock grammar rejected. */
    @Test
    void grammarGapsFoundInRealEstates() {
        ProgramFacts p = one("""
       IDENTIFICATION DIVISION.
       PROGRAM-ID. MAIN.
       ENVIRONMENT DIVISION.
       CONFIGURATION SECTION.
       REPOSITORY.
           FUNCTION ALL INTRINSIC.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT F ASSIGN TO FDD ORGANIZATION IS INDEXED
               RECORD KEY IS F-KEY
               ALTERNATE KEY IS F-ALT WITH DUPLICATES.
       DATA DIVISION.
       FILE SECTION.
       FD F.
       01 F-REC.
          05 F-KEY PIC X.
          05 F-ALT PIC X.
       WORKING-STORAGE SECTION.
       01 COMMA-DATA.
          03 Type PIC X.
          03 Status PIC X.
       01 WS-T PIC X(9).
       PROCEDURE DIVISION.
       S1 SECTION.
       P1.
           MOVE FUNCTION TRIM(WS-T, LEADING) TO WS-T.
           IF WS-T = SPACES
              EXIT SECTION
           END-IF.
           PERFORM UNTIL WS-T = 'X'
              EXIT PERFORM
           END-PERFORM.
           GOBACK.
""");
        assertThat(p.dataItems).extracting(DataItem::name).contains("TYPE", "STATUS");
        assertThat(p.files).extracting(FileFact::name).containsExactly("F");
        assertThat(p.stops).extracting(Stop::loc).extracting(Loc::line).containsExactly(33);
    }

    @Test
    void columnOneCommentsAndLinesOverrunningColumn72() {
        CobolFacts f = new ProLeapCobolParser().parse(CopybookResolver.expand("MAIN", lines("""
*  Copyright 2020, a Micro Focus style comment in column one
/  and a page eject comment
       IDENTIFICATION DIVISION.
       PROGRAM-ID. MAIN.
       PROCEDURE DIVISION.
           DISPLAY 'A LITERAL THAT RUNS PAST COLUMN SEVENTY TWO BEFORE IT CLOSES HERE OK'.
           DISPLAY FUNCTION TRIM('AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA', TRAILING).
           GOBACK.
""").stream().map(com.modtoolkit.parsers.normalize.NormalizedMember::fixedLine).toList(), lib, false));
        assertThat(f.programs.get(0).stops.get(0).loc().line()).isEqualTo(8);
        // A real sequence number past column 72 is still cut.
        assertThat(com.modtoolkit.parsers.normalize.NormalizedMember.fixedLine("000100 MOVE A TO B.                                                     SEQ00001")).isEqualTo("       MOVE A TO B.");
    }

    /** From a real program: END-EXEC crossed column 72, and the block ate the next 24 lines. */
    @Test
    void aLostEndExecDoesNotSwallowTheStatementsAfterIt() {
        String overrun = "                   EXEC CICS LINK PROGRAM('GUSV') COMMAREA(WS-AB-KEY) END-EXEC";
        assertThat(overrun.length()).isGreaterThan(72);
        assertThat(com.modtoolkit.parsers.normalize.NormalizedMember.fixedLine(overrun)).endsWith("END-EXEC");
        // Even a terminator that really is gone cannot take the next statement with it.
        ProgramFacts p = one(HEAD + """
       PROCEDURE DIVISION.
       P1.
           EXEC CICS LINK PROGRAM('AAA') COMMAREA(WS-A)
           EXEC CICS SEND MAP('MAP1') END-EXEC.
           GOBACK.
""");
        assertThat(p.cics).extracting(Cics::command, Cics::resourceName).containsExactly(
                org.assertj.core.groups.Tuple.tuple("LINK", "AAA"), org.assertj.core.groups.Tuple.tuple("SEND", "MAP1"));
        assertThat(p.cics.get(1).loc().line()).isEqualTo(9);
    }

    @Test
    void aCopybookMayShareItsNameWithTheProgram() {
        copy("MAIN", "       01 FROM-COPY PIC X.\n");
        ExpandedSource e = CopybookResolver.expand("MAIN", lines(HEAD + "           COPY MAIN.\n       PROCEDURE DIVISION.\n           GOBACK.\n"), lib, false);
        assertThat(e.missing()).isEmpty();
        assertThat(new ProLeapCobolParser().parse(e).programs.get(0).dataItems).extracting(DataItem::name).contains("FROM-COPY");
    }

    @Test
    void figurativeConstantsAndTextAreNotDynamicCallTargets() {
        ProgramFacts p = one("""
       IDENTIFICATION DIVISION.
       PROGRAM-ID. MAIN.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01 WS-PGM PIC X(20) VALUE SPACES.
       PROCEDURE DIVISION.
           MOVE 'not a program name' TO WS-PGM.
           CALL WS-PGM.
           GOBACK.
""");
        assertThat(p.calls).extracting(Call::kind).containsExactly(CallKind.DYNAMIC_UNRESOLVED);
    }

    @Test
    void dispatchTableVariableIsUnresolvedWithCandidates() {
        ProgramFacts p = one("""
       IDENTIFICATION DIVISION.
       PROGRAM-ID. MAIN.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01 OPT-DATA.
          05 FILLER PIC X(8) VALUE 'COACTVW'.
          05 FILLER PIC X(8) VALUE 'COCRDLI'.
       01 OPT-TAB REDEFINES OPT-DATA.
          05 OPT-ROW OCCURS 2 TIMES.
             10 OPT-PGM PIC X(8).
       01 N PIC 9.
       PROCEDURE DIVISION.
       P1.
           EXEC CICS XCTL PROGRAM(OPT-PGM(N)) END-EXEC.
           CALL OPT-PGM(N).
""");
        assertThat(p.dynamicCandidates).containsEntry("OPT-PGM", List.of("COACTVW", "COCRDLI"));
        assertThat(p.cics.get(0).resourceResolved()).isFalse();
        assertThat(p.calls.get(0).kind()).isEqualTo(CallKind.DYNAMIC_UNRESOLVED);
    }

    @Test
    void cicsLinkIsNotACobolCall() {
        ProgramFacts p = one("""
       IDENTIFICATION DIVISION.
       PROGRAM-ID. MAIN.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01 WS-PGM PIC X(8) VALUE 'XCTLPGM'.
       01 WS-COMM PIC X(8).
       PROCEDURE DIVISION.
       P1.
           EXEC CICS LINK PROGRAM('LINKPGM')
                COMMAREA(WS-COMM)
           END-EXEC.
           EXEC CICS XCTL PROGRAM(WS-PGM) END-EXEC.
           EXEC CICS SEND MAP('MAP1') MAPSET('SET1') END-EXEC.
           EXEC CICS RECEIVE MAP('MAP1') MAPSET('SET1') END-EXEC.
           EXEC CICS READ DATASET('CUSTFILE') INTO(WS-COMM) END-EXEC.
           EXEC CICS RETURN TRANSID('TX01') END-EXEC.
           EXEC CICS RETURN END-EXEC.
""");
        assertThat(p.calls).isEmpty();
        assertThat(p.kind).isEqualTo("cics");
        assertThat(p.cics).extracting(Cics::command, Cics::resourceKind, Cics::resourceName).containsExactly(
                org.assertj.core.groups.Tuple.tuple("LINK", "program", "LINKPGM"),
                org.assertj.core.groups.Tuple.tuple("XCTL", "program", "XCTLPGM"),
                org.assertj.core.groups.Tuple.tuple("SEND", "map", "MAP1"),
                org.assertj.core.groups.Tuple.tuple("RECEIVE", "map", "MAP1"),
                org.assertj.core.groups.Tuple.tuple("READ", "file", "CUSTFILE"),
                org.assertj.core.groups.Tuple.tuple("RETURN", "transaction", "TX01"),
                org.assertj.core.groups.Tuple.tuple("RETURN", null, null));
        assertThat(p.cics.get(0).loc().line()).isEqualTo(9);
        assertThat(p.cics.get(1).loc().line()).isEqualTo(12);
        assertThat(p.cics.get(0).paragraph()).isEqualTo("P1");
        assertThat(p.cics.get(2).attrs()).containsEntry("MAPSET", "'SET1'");
    }

    @Test
    void execSqlTablesColumnsHostVars() {
        ProgramFacts p = one(HEAD + """
       PROCEDURE DIVISION.
           EXEC SQL
             SELECT C1, C2 INTO :H1, :H2
             FROM S1.T1 A JOIN T2 B ON A.X = B.X
             WHERE C3 = :H3
           END-EXEC.
           EXEC SQL INSERT INTO T3 (C1, C2) VALUES (:H1, 'X') END-EXEC.
           EXEC SQL UPDATE T4 SET C1 = :H1, C2 = 0 WHERE C3 = 1 END-EXEC.
           EXEC SQL DELETE FROM T5 WHERE C1 = :H1 END-EXEC.
           EXEC SQL DECLARE C1 CURSOR FOR SELECT A FROM T6 END-EXEC.
           GOBACK.
""");
        assertThat(p.sql).extracting(Sql::stmtType).containsExactly("SELECT", "INSERT", "UPDATE", "DELETE", "DECLARE CURSOR");
        assertThat(p.sql.get(0).tables()).containsExactly("S1.T1", "T2");
        assertThat(p.sql.get(0).columns()).containsExactly("C1", "C2");
        assertThat(p.sql.get(0).hostVars()).containsExactly("H1", "H2", "H3");
        assertThat(p.sql.get(0).loc().line()).isEqualTo(7);
        assertThat(p.sql.get(0).lineEnd()).isEqualTo(11);
        assertThat(p.sql.get(1).tables()).containsExactly("T3");
        assertThat(p.sql.get(2).tables()).containsExactly("T4");
        assertThat(p.sql.get(2).columns()).containsExactly("C1", "C2");
        assertThat(p.sql.get(3).tables()).containsExactly("T5");
        assertThat(p.sql.get(4).tables()).containsExactly("T6");
    }

    @Test
    void execDliAndCbltdliAreRecorded() {
        CobolFacts f = parse(HEAD + """
       PROCEDURE DIVISION.
           EXEC DLI GU SEGMENT(ROOT) END-EXEC.
           CALL 'CBLTDLI' USING WS-A.
           GOBACK.
""");
        assertThat(f.programs.get(0).dli).extracting(Dli::verb).containsExactlyInAnyOrder("EXEC DLI", "CBLTDLI");
        assertThat(f.features).contains("IMS");
    }

    @Test
    void mqQueueNameFollowsMovesToObjectName() {
        ProgramFacts p = one("""
       IDENTIFICATION DIVISION.
       PROGRAM-ID. MAIN.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01 WS-Q PIC X(48) VALUE 'APP.IN.QUEUE'.
       01 MQOD-OBJECTNAME PIC X(48).
       01 HC PIC S9(9) COMP.
       PROCEDURE DIVISION.
           MOVE WS-Q TO MQOD-OBJECTNAME.
           CALL 'MQOPEN' USING HC.
           CALL 'MQGET' USING HC.
           GOBACK.
""");
        assertThat(p.mq).extracting(Mq::verb, Mq::queue).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("MQOPEN", null), org.assertj.core.groups.Tuple.tuple("MQGET", "APP.IN.QUEUE"));
    }

    @Test
    void nestedProgramsEachGetTheirOwnFacts() {
        CobolFacts f = parse("""
       IDENTIFICATION DIVISION.
       PROGRAM-ID. OUTER.
       PROCEDURE DIVISION.
       O1.
           CALL 'INNER'.
           EXEC CICS RETURN END-EXEC.
       IDENTIFICATION DIVISION.
       PROGRAM-ID. INNER.
       PROCEDURE DIVISION.
       I1.
           CALL 'LEAF'.
           EXEC SQL DELETE FROM T1 END-EXEC.
           GOBACK.
       END PROGRAM INNER.
       END PROGRAM OUTER.
""");
        assertThat(f.programs).extracting(q -> q.programId).containsExactlyInAnyOrder("OUTER", "INNER");
        ProgramFacts inner = f.programs.stream().filter(q -> q.programId.equals("INNER")).findFirst().orElseThrow();
        ProgramFacts outer = f.programs.stream().filter(q -> q.programId.equals("OUTER")).findFirst().orElseThrow();
        assertThat(inner.parent).isEqualTo("OUTER");
        assertThat(inner.calls).extracting(Call::target).containsExactly("LEAF");
        assertThat(outer.calls).extracting(Call::target).containsExactly("INNER");
        assertThat(inner.sql).hasSize(1);
        assertThat(outer.sql).isEmpty();
        assertThat(outer.cics).hasSize(1);
    }

    @Test
    void copyReplacingPseudoTextAndProvenanceThroughExpansion() {
        copy("CUST", """
       01 :TAG:-REC.
           05 :TAG:-ID PIC X(4).
           05 OLD-NAME PIC X(10).
""");
        ExpandedSource e = CopybookResolver.expand("MAIN", lines(HEAD + """
           COPY CUST REPLACING ==:TAG:== BY ==WS==
                               OLD-NAME BY NEW-NAME.
       PROCEDURE DIVISION.
           MOVE 'A' TO WS-ID.
           GOBACK.
"""), lib, false);
        assertThat(e.copies()).hasSize(1);
        assertThat(e.copies().get(0).replacing()).containsEntry("==:TAG:==", "==WS==").containsEntry("OLD-NAME", "NEW-NAME");
        ProgramFacts p = new ProLeapCobolParser().parse(e).programs.get(0);
        DataItem ws = p.dataItems.stream().filter(d -> "WS-ID".equals(d.name())).findFirst().orElseThrow();
        assertThat(ws.loc().member()).isEqualTo("CUST");
        assertThat(ws.loc().line()).isEqualTo(2);
        assertThat(ws.offset()).isZero();
        assertThat(p.dataItems).extracting(DataItem::name).contains("NEW-NAME", "WS-REC");
        // Review Focus 4: a statement after the expansion keeps its own member's line.
        assertThat(p.moves.get(0).loc()).isEqualTo(new Loc("MAIN", 9));
    }

    @Test
    void nestedCopyMissingAndSystemCopybooksAndCycles() {
        copy("OUTERC", "           COPY INNERC.\n           COPY NOPE.\n");
        copy("INNERC", "       01 I-REC PIC X.\n           COPY INNERC.\n");
        ExpandedSource e = CopybookResolver.expand("MAIN", lines(HEAD + "           COPY OUTERC.\n           COPY DFHAID.\n       PROCEDURE DIVISION.\n           GOBACK.\n"), lib, false);
        assertThat(e.missing()).contains("NOPE", "INNERC (cycle)").doesNotContain("DFHAID");
        assertThat(new ProLeapCobolParser().parse(e).programs.get(0).dataItems).extracting(DataItem::name).contains("I-REC");
    }

    /** Review Focus 3. */
    @Test
    void duplicateCopybookNamesResolveDeterministicallyAndAreReported() {
        lib.add("dup", "z/long/path/DUP.cpy", lines("       01 FROM-LONG PIC X.\n"));
        lib.add("DUP", "a/DUP.cpy", lines("       01 FROM-SHORT PIC X.\n"));
        ExpandedSource e = CopybookResolver.expand("MAIN", lines(HEAD + "           COPY dup.\n       PROCEDURE DIVISION.\n           GOBACK.\n"), lib, false);
        assertThat(e.duplicates().get(0).paths()).containsExactly("a/DUP.cpy", "z/long/path/DUP.cpy");
        assertThat(new ProLeapCobolParser().parse(e).programs.get(0).dataItems).extracting(DataItem::name).contains("FROM-SHORT").doesNotContain("FROM-LONG");
    }

    @Test
    void aRealCopybookWinsOverAnUnclassifiedMemberWithTheSameName() {
        lib.add("SAME", "s/SAME", lines("       01 FROM-MAYBE PIC X.\n"), false);
        lib.add("SAME", "some/much/longer/path/SAME.cpy", lines("       01 FROM-REAL PIC X.\n"), true);
        ExpandedSource e = CopybookResolver.expand("MAIN", lines(HEAD + "           COPY SAME.\n       PROCEDURE DIVISION.\n           GOBACK.\n"), lib, false);
        assertThat(new ProLeapCobolParser().parse(e).programs.get(0).dataItems).extracting(DataItem::name).contains("FROM-REAL").doesNotContain("FROM-MAYBE");
    }

    @Test
    void copyInsideExecSqlIncludeExpands() {
        copy("DCLT1", """
           EXEC SQL DECLARE T1 TABLE (C1 CHAR(1) NOT NULL, C2 INT) END-EXEC.
       01 DCLT1.
           05 C1 PIC X.
""");
        ProgramFacts p = one(HEAD + """
           EXEC SQL INCLUDE DCLT1 END-EXEC.
           EXEC SQL INCLUDE SQLCA END-EXEC.
       PROCEDURE DIVISION.
           GOBACK.
""");
        assertThat(p.dataItems).extracting(DataItem::name).contains("C1");
        assertThat(p.sql).extracting(Sql::stmtType).contains("DECLARE TABLE");
        assertThat(p.sql.stream().filter(s -> s.stmtType().equals("DECLARE TABLE")).findFirst().orElseThrow().columns()).containsExactly("C1", "C2");
    }

    @Test
    void replaceStatementIsHonoured() {
        ProgramFacts p = one("""
       IDENTIFICATION DIVISION.
       PROGRAM-ID. MAIN.
       REPLACE ==OLDNAME== BY ==NEWNAME==.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01 OLDNAME PIC X.
       PROCEDURE DIVISION.
           MOVE 'A' TO OLDNAME.
           GOBACK.
""");
        assertThat(p.dataItems).extracting(DataItem::name).containsExactly("NEWNAME");
        assertThat(p.moves.get(0).loc().line()).isEqualTo(8);
    }

    @Test
    void dataItemsCarryCompWidthsOdoRedefinesAnd88s() {
        ProgramFacts p = one("""
       IDENTIFICATION DIVISION.
       PROGRAM-ID. MAIN.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01 REC.
           05 CNT PIC S9(4) COMP.
           05 AMT PIC S9(7)V99 COMP-3.
           05 RAW PIC X(4).
           05 VIEW1 REDEFINES RAW PIC 9(4).
              88 VIEW-OK VALUE 1 THRU 9.
              88 VIEW-BAD VALUES 10, 20.
           05 TBL OCCURS 1 TO 5 TIMES DEPENDING ON CNT.
              10 ELT PIC X(3).
       PROCEDURE DIVISION.
           GOBACK.
""");
        DataItem amt = byName(p, "AMT");
        assertThat(amt.usage()).isEqualTo("COMP-3");
        assertThat(amt.offset()).isEqualTo(2);
        assertThat(amt.length()).isEqualTo(5);
        DataItem view = byName(p, "VIEW1");
        assertThat(view.redefines()).isEqualTo("RAW");
        assertThat(view.offset()).isEqualTo(7);
        DataItem tbl = byName(p, "TBL");
        assertThat(tbl.odoRef()).isEqualTo("CNT");
        assertThat(tbl.occursMin()).isEqualTo(1);
        assertThat(tbl.occursMax()).isEqualTo(5);
        assertThat(byName(p, "REC").length()).isEqualTo(26);
        assertThat(p.conditions).extracting(Condition::name).containsExactly("VIEW-OK", "VIEW-BAD");
        assertThat(p.conditions.get(0).values()).containsExactly("1 THRU 9");
        assertThat(p.dataItems.get(p.conditions.get(0).dataItem()).name()).isEqualTo("VIEW1");
    }

    private static DataItem byName(ProgramFacts p, String n) {
        return p.dataItems.stream().filter(d -> n.equals(d.name())).findFirst().orElseThrow();
    }

    @Test
    void movesComputeFilesSortAndMetrics() {
        ProgramFacts p = one("""
       IDENTIFICATION DIVISION.
       PROGRAM-ID. MAIN.
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT INF ASSIGN TO INDD.
           SELECT WRK ASSIGN TO SORTWK.
           SELECT OUTF ASSIGN TO OUTDD.
       DATA DIVISION.
       FILE SECTION.
       FD INF.
       01 IN-REC PIC X(80).
       SD WRK.
       01 W-REC PIC X(80).
       FD OUTF.
       01 OUT-REC PIC X(80).
       WORKING-STORAGE SECTION.
       01 A PIC 9.
       01 B PIC 9.
       01 C PIC 9.
       PROCEDURE DIVISION.
       P1.
           OPEN INPUT INF OUTPUT OUTF.
           READ INF AT END MOVE 1 TO A.
           IF A = 1
              IF B = 2
                 COMPUTE C = A + B
              END-IF
           END-IF.
           MOVE A TO B C.
           WRITE OUT-REC.
           SORT WRK ON ASCENDING KEY W-REC USING INF GIVING OUTF.
           STOP RUN.
""");
        assertThat(p.files).extracting(FileFact::name, FileFact::assign).contains(org.assertj.core.groups.Tuple.tuple("INF", "INDD"));
        assertThat(p.fileIo).extracting(FileIo::verb, FileIo::file).contains(
                org.assertj.core.groups.Tuple.tuple("OPEN INPUT", "INF"), org.assertj.core.groups.Tuple.tuple("READ", "INF"),
                org.assertj.core.groups.Tuple.tuple("WRITE", "OUT-REC"), org.assertj.core.groups.Tuple.tuple("OPEN OUTPUT", "OUTF"));
        assertThat(p.moves).extracting(Move::kind, Move::from, Move::to).contains(
                org.assertj.core.groups.Tuple.tuple("MOVE", "A", "B"), org.assertj.core.groups.Tuple.tuple("MOVE", "A", "C"),
                org.assertj.core.groups.Tuple.tuple("COMPUTE", "A", "C"), org.assertj.core.groups.Tuple.tuple("COMPUTE", "B", "C"));
        assertThat(p.sorts.get(0).using()).containsExactly("INF");
        assertThat(p.sorts.get(0).giving()).containsExactly("OUTF");
        // Hand count: base 1 + AT END + IF + IF = 4; nesting 2.
        assertThat(p.cyclomatic).isEqualTo(4);
        assertThat(p.paragraphs.get(0).complexity()).isEqualTo(4);
        assertThat(p.maxNesting).isEqualTo(2);
        assertThat(p.loc).isEqualTo(33);
    }

    @Test
    void syntaxErrorReportsItsPositionInTheOriginalMember() {
        copy("BADC", "       01 OK PIC X.\n");
        assertThatThrownBy(() -> parse(HEAD + "           COPY BADC.\n       PROCEDURE DIVISION.\n           MOVE TO TO TO.\n"))
                .isInstanceOfSatisfying(CobolParseException.class, e -> {
                    assertThat(e.at()).isEqualTo(new Loc("MAIN", 8));
                });
    }

    /** Review Focus 2. */
    @Test
    void hangingOrExplodingParserBecomesAFailedOutcomeAndTheFallbackRuns() {
        CobolParser hang = new CobolParser() {
            @Override public String name() { return "hang"; }
            @Override public CobolFacts parse(ExpandedSource s) {
                while (true) {
                    try { Thread.sleep(50); } catch (InterruptedException e) { throw new CobolParseException("stopped", null); }
                }
            }
        };
        CobolParser oom = new CobolParser() {
            @Override public String name() { return "oom"; }
            @Override public CobolFacts parse(ExpandedSource s) { throw new OutOfMemoryError("boom"); }
        };
        CobolParser so = new CobolParser() {
            @Override public String name() { return "so"; }
            @Override public CobolFacts parse(ExpandedSource s) { return parse(s); }
        };
        ExpandedSource src = CopybookResolver.expand("MAIN", lines(HEAD + "       PROCEDURE DIVISION.\n           GOBACK.\n"), lib, false);
        var failed = new ResilientParser(List.of(hang, oom), Duration.ofMillis(300)).parse(src);
        assertThat(failed.ok()).isFalse();
        assertThat(failed.rejectedBy()).hasSize(2);
        assertThat(failed.rejectedBy().get(0)).contains("timed out");
        assertThat(failed.rejectedBy().get(1)).contains("OutOfMemoryError");
        assertThat(new ResilientParser(List.of(so), Duration.ofMinutes(2)).parse(src).error()).contains("StackOverflowError");
        var ok = new ResilientParser(List.of(oom, new ProLeapCobolParser()), Duration.ofSeconds(30)).parse(src);
        assertThat(ok.ok()).isTrue();
        assertThat(ok.parser()).isEqualTo("proleap");
    }

    @Test
    void realDeepRecursionDoesNotOverflow() {
        StringBuilder sb = new StringBuilder(HEAD + "       PROCEDURE DIVISION.\n");
        for (int i = 0; i < 400; i++) sb.append("           IF WS-A = 'X'\n");
        sb.append("           DISPLAY 'Y'\n");
        for (int i = 0; i < 400; i++) sb.append("           END-IF\n");
        sb.append("           GOBACK.\n");
        ExpandedSource src = CopybookResolver.expand("MAIN", lines(sb.toString()), lib, false);
        var o = new ResilientParser(List.of(new ProLeapCobolParser()), Duration.ofMinutes(2)).parse(src);
        assertThat(o.ok()).as(String.valueOf(o.error())).isTrue();
        assertThat(o.facts().programs.get(0).maxNesting).isEqualTo(400);
    }
}
