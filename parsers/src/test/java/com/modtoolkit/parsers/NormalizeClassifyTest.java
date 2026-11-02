package com.modtoolkit.parsers;

import static org.assertj.core.api.Assertions.assertThat;

import com.modtoolkit.parsers.classify.Classifier;
import com.modtoolkit.parsers.classify.MemberType;
import com.modtoolkit.parsers.normalize.NormalizedMember;
import com.modtoolkit.parsers.normalize.Normalizer;
import com.modtoolkit.parsers.normalize.SourceOptions;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NormalizeClassifyTest {
    private static final String PROGRAM = """
       IDENTIFICATION DIVISION.
       PROGRAM-ID. HELLO.
       PROCEDURE DIVISION.
           DISPLAY 'HI [1]'.
           GOBACK.
""";

    private NormalizedMember norm(String s) {
        return Normalizer.normalize(s.getBytes(StandardCharsets.UTF_8), SourceOptions.AUTO);
    }

    private MemberType type(String s) {
        return Classifier.classify(norm(s)).type();
    }

    @ParameterizedTest
    @CsvSource({"IBM037,cp037", "IBM1047,cp1047"})
    void ebcdicIsDetectedAndDecoded(String cs, String expected) {
        byte[] raw = PROGRAM.replace("\n", "\u0085").getBytes(Charset.forName(cs));
        NormalizedMember m = Normalizer.normalize(raw, SourceOptions.AUTO);
        assertThat(m.encoding()).isEqualTo(expected);
        assertThat(m.lines().get(1)).contains("PROGRAM-ID. HELLO.");
        assertThat(Classifier.classify(m).type()).isEqualTo(MemberType.COBOL);
    }

    @Test
    void ebcdicFixed80ByteRecordsWithNoLineEndsAreSplit() {
        StringBuilder sb = new StringBuilder();
        for (String l : PROGRAM.split("\n")) sb.append(String.format("%-80s", l));
        NormalizedMember m = Normalizer.normalize(sb.toString().getBytes(Charset.forName("IBM037")), SourceOptions.AUTO);
        assertThat(m.lines()).hasSize(5);
        assertThat(m.lines().get(4)).isEqualTo("           GOBACK.");
    }

    @Test
    void sequenceColumnsAreStrippedAndHashKeptOverOriginalBytes() {
        byte[] raw = "000100 IDENTIFICATION DIVISION.                                         AAAA0001\n".getBytes(StandardCharsets.UTF_8);
        NormalizedMember m = Normalizer.normalize(raw, SourceOptions.AUTO);
        assertThat(m.format()).isEqualTo(NormalizedMember.Format.FIXED);
        assertThat(m.cobolLines().get(0)).isEqualTo("       IDENTIFICATION DIVISION.");
        assertThat(m.sha256()).isEqualTo(Normalizer.sha256(raw));
        assertThat(m.originalLine(1)).isEqualTo(1);
    }

    @Test
    void freeFormatDetected() {
        assertThat(norm(">>SOURCE FORMAT FREE\nIDENTIFICATION DIVISION.\nPROGRAM-ID. X.\n").format()).isEqualTo(NormalizedMember.Format.FREE);
        assertThat(norm("IDENTIFICATION DIVISION.\nPROGRAM-ID. X.\nPROCEDURE DIVISION.\nDISPLAY 'A'.\n").format()).isEqualTo(NormalizedMember.Format.FREE);
    }

    @Test
    void crlfHandled() {
        assertThat(norm("       ID DIVISION.\r\n       PROGRAM-ID. X.\r\n").lines()).containsExactly("       ID DIVISION.", "       PROGRAM-ID. X.");
    }

    @Test
    void classifiesBySignatureOnly() {
        assertThat(type(PROGRAM)).isEqualTo(MemberType.COBOL);
        assertThat(type("//PAYROLL JOB (ACCT),'X',CLASS=A\n//S1 EXEC PGM=IEFBR14\n")).isEqualTo(MemberType.JCL);
        assertThat(type("//MYPROC PROC HLQ=A\n//S1 EXEC PGM=X\n// PEND\n")).isEqualTo(MemberType.PROC);
        assertThat(type("MAPSET1  DFHMSD TYPE=&SYSPARM,MODE=INOUT\n")).isEqualTo(MemberType.BMS);
        assertThat(type("  CREATE TABLE T1 (A INT);\n")).isEqualTo(MemberType.DDL);
        assertThat(type("       01  CUST-REC.\n           05 NAME PIC X(10).\n")).isEqualTo(MemberType.COPYBOOK);
        assertThat(type("           05 NAME PIC X(10).\n")).isEqualTo(MemberType.COPYBOOK);
        assertThat(type("DEFINE TRANSACTION(CC00) GROUP(G) PROGRAM(P1)\n")).isEqualTo(MemberType.CSD);
        assertThat(type("/* REXX */\nsay 'hi'\n")).isEqualTo(MemberType.REXX);
        assertThat(type("  MAIN: PROC OPTIONS(MAIN);\n")).isEqualTo(MemberType.PLI);
        assertThat(type("PROG1    CSECT\n         BALR  12,0\n")).isEqualTo(MemberType.ASSEMBLER);
        assertThat(type("  SORT FIELDS=(1,10,CH,A)\n")).isEqualTo(MemberType.CONTROL);
    }

    @Test
    void aCommentMentioningAKeywordDoesNotDecideType() {
        assertThat(type("      * IDENTIFICATION DIVISION.\n       01 A PIC X.\n")).isEqualTo(MemberType.COPYBOOK);
    }

    /** Each is a real file that was misread: a tutorial, a license, a PL/I member, a numbered list. */
    @Test
    void documentsAndPliAreNotCobolOrCopybooks() {
        assertThat(type("# Lab\n\nHere is a program:\n\n       IDENTIFICATION DIVISION.\n       PROGRAM-ID. X.\n")).isNotEqualTo(MemberType.COBOL);
        assertThat(type("Copyright 2020\n\n1. Permission is granted to copy this\n2. Redistribution in any form\n3 Terms\n")).isEqualTo(MemberType.UNKNOWN);
        assertThat(type(" /* SIST ENDRET */\n DCL 1 A,\n     2 B CHAR(3),\n     2 C CHAR(3);\n X = 1;\n")).isEqualTo(MemberType.PLI);
        assertThat(type(" %INCLUDE P0014002;\n /* COMMENT */\n W = 1;\n Y = 2;\n")).isEqualTo(MemberType.PLI);
        assertThat(type("      * CBL DATA,NODYNAM\n       IDENTIFICATION DIVISION.\n       PROGRAM-ID. X.\n")).isEqualTo(MemberType.COBOL);
        assertThat(type(" CBL OPT(2)\n       IDENTIFICATION DIVISION.\n       PROGRAM-ID. X.\n")).isEqualTo(MemberType.COBOL);
    }

    @Test
    void templatedJclAndScriptLanguagesAreNotMisread() {
        assertThat(type("//<WUIAPPL> JOB 241901,'WUI',CLASS=A\n//GENAPP1  EXEC PGM=DFHSIP\n")).isEqualTo(MemberType.JCL);
        assertThat(type("/*****/\n/* (C) */\n/*****/\nmsgtxt(ssc1a1)\ntext('hello');\nwait;\n")).isNotEqualTo(MemberType.PLI);
    }

    /** From a real estate: a PL/I include with a COBOL copybook's name took that copybook's place in five programs. */
    @Test
    void aDocumentQuotingCicsDefinitionsIsNotACsd() {
        assertThat(type("# CardDemo\n\nThis application is a sample that shows how the online system is put together.\n\n```\nDEFINE TRANSACTION(CA00) GROUP(CARDDEMO)\n```\n\n- [Guide](http://example.com)\n")).isEqualTo(MemberType.UNKNOWN);
    }

    @Test
    void anIncludedPliStructureIsNotACobolCopybook() {
        assertThat(type("  /* CIOFUNCS.INC */\n     05  IO_AREAS,\n       10  IO_REQ       CHAR(5),\n       10  IO_STAT      CHAR(2),\n       10  IO_LAST      CHAR(2);\n")).isEqualTo(MemberType.PLI);
        assertThat(type("  /* X */\n     05  IO_AREAS,\n       10  IO_FUNC   CHAR(8),\n         /*  IO-OPEN VALUE 'OPEN'.\n         88  IO-READ VALUE 'READ'.\n         88  IO-CLOSE VALUE 'CLOSE'*/\n")).isEqualTo(MemberType.PLI);
        assertThat(type("       05  IO-AREAS.\n         10  IO-REQ  PIC X(5).\n         10  IO-STAT PIC XX.\n         10  IO-LAST PIC XX.\n")).isEqualTo(MemberType.COPYBOOK);
    }

    @Test
    void aReadmeWithCodeSamplesIsNotPli() {
        String md = "# The Authorization Module\n\nThis module handles pending authorizations for the online system and more.\n\n```sql\nDECLARE C1 CURSOR FOR SELECT A;\nSET X = 1;\nSET Y = 2;\n/* a comment */\n```\n\n## Usage\n\nSee the [guide](http://example.com/guide) for details on how it is all put together.\n";
        assertThat(type(md)).isEqualTo(MemberType.UNKNOWN);
    }

    @Test
    void aStrayNulInLongTextIsStillText() {
        String pli = " /* SIST ENDRET PROD */\n DCL A CHAR(1);\n".repeat(50);
        byte[] b = (pli.substring(0, 100) + "\u0000" + pli.substring(100)).getBytes(StandardCharsets.UTF_8);
        NormalizedMember m = Normalizer.normalize(b, SourceOptions.AUTO);
        assertThat(m.binary()).isFalse();
        assertThat(Classifier.classify(m).type()).isEqualTo(MemberType.PLI);
    }

    @Test
    void blankAndCommentLinesBeforeTheIdentificationDivisionDoNotHideIt() {
        assertThat(type("\n\n      * header\n\n000300\n       IDENTIFICATION DIVISION.\n       PROGRAM-ID.\n           X.\n")).isEqualTo(MemberType.COBOL);
        assertThat(type("      >>SOURCE FORMAT FIXED\n       ID DIVISION.\n       PROGRAM-ID. X.\n")).isEqualTo(MemberType.COBOL);
    }

    @Test
    void longLinesDoNotMakeAFixedFormatMemberFree() {
        String longLine = "      * " + "X".repeat(120) + "\n";
        String src = longLine.repeat(30) + "       IDENTIFICATION DIVISION.\n       PROGRAM-ID. X.\n";
        assertThat(norm(src).format()).isEqualTo(NormalizedMember.Format.FIXED);
        assertThat(type(src)).isEqualTo(MemberType.COBOL);
    }

    /** Review Focus 1. */
    @Test
    void binaryEmptyAndUnrecognizedAreUnknownWithAReasonNotACrash() {
        byte[] bin = new byte[4096];
        for (int i = 0; i < bin.length; i++) bin[i] = (byte) (i % 7 == 0 ? 0 : i);
        NormalizedMember m = Normalizer.normalize(bin, SourceOptions.AUTO);
        assertThat(Classifier.classify(m).type()).isEqualTo(MemberType.UNKNOWN);
        assertThat(Classifier.classify(m).reason()).isEqualTo("binary content");
        assertThat(Classifier.classify(norm("")).reason()).isEqualTo("empty");
        assertThat(Classifier.classify(norm("   \n\n")).reason()).isEqualTo("empty");
        assertThat(Classifier.classify(norm("hello there, this is a readme\n")).reason()).isEqualTo("no known content signature");
    }
}
