package com.modtoolkit.parsers;

import static org.assertj.core.api.Assertions.assertThat;

import com.modtoolkit.parsers.bms.BmsParser;
import com.modtoolkit.parsers.csd.CsdParser;
import com.modtoolkit.parsers.ddl.DdlParser;
import com.modtoolkit.parsers.jcl.JclParser;
import com.modtoolkit.parsers.jcl.JclParser.*;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StructuredParsersTest {
    private static List<String> l(String s) {
        return Arrays.asList(s.split("\n", -1));
    }

    private final Map<String, ProcBody> procs = new HashMap<>();

    private Job job(String src) {
        List<Job> jobs = JclParser.parse("JOBM", l(src), procs::get);
        assertThat(jobs).hasSize(1);
        return jobs.get(0);
    }

    @Test
    void jobStepsDdsDispContinuationAndComments() {
        Job j = job("""
//PAY01 JOB (ACCT),'X',CLASS=A,RESTART=STEP2
//* A comment line
//STEP1 EXEC PGM=PAYROLL,COND=(4,LT),
//             PARM='A,B'
//INFILE DD DSN=PROD.PAY.IN,DISP=SHR
//       DD DSN=PROD.PAY.IN2,DISP=SHR
//OUTFILE DD DSN=PROD.PAY.OUT(+1),
//           DISP=(NEW,CATLG,DELETE),UNIT=SYSDA  comment here
//SYSOUT DD SYSOUT=*
//SYSIN DD *
  SORT FIELDS=(1,10,CH,A)
/*
//STEP2 EXEC PGM=IEFBR14
""");
        assertThat(j.name()).isEqualTo("PAY01");
        assertThat(j.params()).containsEntry("RESTART", "STEP2");
        assertThat(j.steps()).extracting(Step::name).containsExactly("STEP1", "STEP2");
        Step s = j.steps().get(0);
        assertThat(s.pgm()).isEqualTo("PAYROLL");
        assertThat(s.cond()).isEqualTo("(4,LT)");
        assertThat(s.params().get("PARM")).isEqualTo("'A,B'");
        assertThat(s.line()).isEqualTo(3);
        assertThat(s.dds()).extracting(Dd::name).containsExactly("INFILE", "INFILE", "OUTFILE", "SYSOUT", "SYSIN");
        Dd out = s.dds().get(2);
        assertThat(out.dsn()).isEqualTo("PROD.PAY.OUT");
        assertThat(out.gdgRel()).isEqualTo(1);
        assertThat(out.disp()).isEqualTo("(NEW,CATLG,DELETE)");
        assertThat(out.line()).isEqualTo(7);
        assertThat(s.dds().get(1).dsn()).isEqualTo("PROD.PAY.IN2");
        assertThat(j.controlText().get(0)).contains("SORT FIELDS");
    }

    @Test
    void gdgRelativeGenerations() {
        Job j = job("//J JOB\n//S EXEC PGM=X\n//A DD DSN=A.B(0),DISP=SHR\n//B DD DSN=A.B(-1),DISP=SHR\n//C DD DSN=A.B,DISP=SHR\n");
        assertThat(j.steps().get(0).dds()).extracting(Dd::gdgRel).containsExactly(0, -1, null);
    }

    @Test
    void procExpansionWithDefaultsOverridesAndNestedSymbolics() {
        procs.put("OUTER", new ProcBody("OUTERM", l("""
//OUTER PROC HLQ=PROD,ENV=&HLQ..ENV,MEMB=DFLT
//OS1 EXEC PGM=OUTPGM
//DATA DD DSN=&ENV..&MEMB..DATA,DISP=SHR
//OS2 EXEC INNER,SUFFIX=&MEMB
// PEND
""")));
        procs.put("INNER", new ProcBody("INNERM", l("""
//INNER PROC SUFFIX=X
//IS1 EXEC PGM=INNPGM
//OUT DD DSN=&HLQ..&SUFFIX..OUT(+1),DISP=(NEW,CATLG)
""")));
        Job j = job("""
//J JOB
// SET HLQ=TEST
//RUN EXEC PROC=OUTER,MEMB=ABC
//OS1.DATA DD DSN=OVERRIDE.DATA,DISP=OLD
""");
        assertThat(j.missingProcs()).isEmpty();
        assertThat(j.steps()).extracting(Step::pgm).containsExactly("OUTPGM", "INNPGM");
        assertThat(j.steps()).extracting(Step::seq).containsExactly(1, 2);
        Step outer = j.steps().get(0);
        assertThat(outer.member()).isEqualTo("OUTERM");
        assertThat(outer.line()).isEqualTo(2);
        assertThat(outer.proc()).isEqualTo("OUTER");
        assertThat(outer.dds().get(0).dsn()).isEqualTo("OVERRIDE.DATA");
        assertThat(outer.dds().get(0).disp()).isEqualTo("OLD");
        Step inner = j.steps().get(1);
        assertThat(inner.member()).isEqualTo("INNERM");
        assertThat(inner.dds().get(0).dsn()).isEqualTo("PROD.ABC.OUT");
        assertThat(inner.dds().get(0).gdgRel()).isEqualTo(1);
    }

    @Test
    void symbolWithoutAValueIsLeftVisibleAndTempDatasetsAreUntouched() {
        Job j = job("//J JOB\n//S EXEC PGM=X\n//A DD DSN=&&TEMP,DISP=(NEW,PASS)\n//B DD DSN=&UNSET..X,DISP=SHR\n");
        assertThat(j.steps().get(0).dds().get(0).dsn()).isEqualTo("&&TEMP");
        assertThat(j.steps().get(0).dds().get(1).dsn()).isEqualTo("&UNSET..X");
    }

    @Test
    void missingAndRecursiveProcsAreReported() {
        procs.put("LOOP", new ProcBody("LOOPM", l("//LOOP PROC\n//S EXEC LOOP\n")));
        Job j = job("//J JOB\n//A EXEC NOPROC\n//B EXEC LOOP\n");
        assertThat(j.missingProcs()).containsExactly("NOPROC", "LOOP (recursive)");
        assertThat(j.steps().get(0).proc()).isEqualTo("NOPROC");
    }

    @Test
    void instreamProcAndIfCondition() {
        Job j = job("""
//J JOB
//P1 PROC
//PS EXEC PGM=INPROC
// PEND
//S1 EXEC P1
// IF (S1.PS.RC = 0) THEN
//S2 EXEC PGM=ONOK
// ELSE
//S3 EXEC PGM=ONBAD
// ENDIF
//S4 EXEC PGM=ALWAYS
""");
        assertThat(j.steps()).extracting(Step::pgm).containsExactly("INPROC", "ONOK", "ONBAD", "ALWAYS");
        assertThat(j.steps().get(1).ifCond()).isEqualTo("(S1.PS.RC = 0)");
        assertThat(j.steps().get(2).ifCond()).startsWith("NOT");
        assertThat(j.steps().get(3).ifCond()).isNull();
    }

    @Test
    void db2BatchProgramComesFromSystsinAndImsFromParm() {
        Job j = job("""
//J JOB
//S1 EXEC PGM=IKJEFT01
//SYSTSIN DD *
 DSN SYSTEM(DB2P)
 RUN PROGRAM(COBTUPDT) PLAN(P1)
 END
/*
//S2 EXEC PGM=DFSRRC00,PARM='DLI,PAUDBUNL,PSB1'
""");
        assertThat(j.steps().get(0).pgm()).isEqualTo("COBTUPDT");
        assertThat(j.steps().get(0).params()).containsEntry("RUN_VIA", "IKJEFT01");
        assertThat(JclParser.imsProgram(j.steps().get(1))).isEqualTo("PAUDBUNL");
    }

    @Test
    void bmsMapsFieldsAndContinuations() {
        String c72 = String.format("%-71sX", "SIGNA    DFHMDI COLUMN=1,LINE=1,SIZE=(24,80),");
        List<BmsParser.BmsMap> maps = BmsParser.parse(l("""
SET1     DFHMSD TYPE=&SYSPARM,MODE=INOUT,LANG=COBOL
""" + c72 + "\n" + """
               CTRL=(FREEKB)
         DFHMDF POS=(1,1),LENGTH=10,ATTRB=(ASKIP,BRT),INITIAL='SIGN,ON'
USERID   DFHMDF POS=(5,20),LENGTH=8,ATTRB=(UNPROT,IC)
         DFHMSD TYPE=FINAL
         END
"""));
        assertThat(maps).hasSize(1);
        assertThat(maps.get(0).mapset()).isEqualTo("SET1");
        assertThat(maps.get(0).name()).isEqualTo("SIGNA");
        assertThat(maps.get(0).line()).isEqualTo(2);
        assertThat(maps.get(0).attrs()).containsEntry("CTRL", "(FREEKB)");
        var f = maps.get(0).fields();
        assertThat(f).hasSize(2);
        assertThat(f.get(0).attrs()).containsEntry("INITIAL", "SIGN,ON");
        assertThat(f.get(1).name()).isEqualTo("USERID");
        assertThat(f.get(1).row()).isEqualTo(5);
        assertThat(f.get(1).col()).isEqualTo(20);
        assertThat(f.get(1).length()).isEqualTo(8);
        assertThat(f.get(1).line()).isEqualTo(5);
    }

    @Test
    void ddlTablesColumnsKeys() {
        List<DdlParser.Table> t = DdlParser.parse("""
-- a comment with CREATE TABLE NOPE (X INT)
CREATE TABLE CARD.ACCT (
  ACCT_ID   CHAR(11)  NOT NULL,
  BAL       DECIMAL(10, 2) WITH DEFAULT 0,
  NAME      VARCHAR(40),
  PRIMARY KEY (ACCT_ID, NAME)
) IN DB.TS;
CREATE UNIQUE INDEX IX1 ON CARD.ACCT (ACCT_ID);
CREATE TABLE T2 (K INT NOT NULL PRIMARY KEY, V CHAR(1));
CREATE TABLE T3 (A INT NOT NULL);
ALTER TABLE T3 ADD CONSTRAINT PK PRIMARY KEY (A);
""");
        assertThat(t).extracting(DdlParser.Table::name).containsExactly("ACCT", "T2", "T3");
        DdlParser.Table a = t.get(0);
        assertThat(a.schema()).isEqualTo("CARD");
        assertThat(a.line()).isEqualTo(2);
        assertThat(a.columns()).extracting(DdlParser.Column::name).containsExactly("ACCT_ID", "BAL", "NAME");
        assertThat(a.columns().get(0).nullable()).isFalse();
        assertThat(a.columns().get(1).type()).isEqualTo("DECIMAL(10, 2)");
        assertThat(a.columns().get(1).nullable()).isTrue();
        assertThat(a.columns().get(0).keyPos()).isEqualTo(1);
        assertThat(a.columns().get(2).keyPos()).isEqualTo(2);
        assertThat(a.columns().get(0).line()).isEqualTo(3);
        assertThat(t.get(1).columns().get(0).keyPos()).isEqualTo(1);
        assertThat(t.get(2).columns().get(0).keyPos()).isEqualTo(1);
    }

    @Test
    void csdDefinitions() {
        List<CsdParser.Definition> d = CsdParser.parse(l("""
* comment DEFINE TRANSACTION(NOPE)
DEFINE TRANSACTION(CC00) GROUP(CARD)
       PROGRAM(COSGN00C) DESCRIPTION(SIGN ON)
DEFINE FILE(ACCTDAT) GROUP(CARD) DSNAME(AWS.ACCT.VSAM)
LIST GROUP(CARD)
"""));
        assertThat(d).extracting(CsdParser.Definition::kind).containsExactly("TRANSACTION", "FILE");
        assertThat(d.get(0).name()).isEqualTo("CC00");
        assertThat(d.get(0).attrs()).containsEntry("PROGRAM", "COSGN00C");
        assertThat(d.get(0).line()).isEqualTo(2);
        assertThat(d.get(1).attrs()).containsEntry("DSNAME", "AWS.ACCT.VSAM");
    }
}
