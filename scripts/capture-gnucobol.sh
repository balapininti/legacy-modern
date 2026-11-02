#!/usr/bin/env bash
# Runs CardDemo's CBACT04C for real under GnuCOBOL in Docker, through TKDRIVER, and saves every input and output as raw
# bytes. This stands in for a test LPAR, which this project has no access to: the COBOL really executes, on the same
# record layouts, but it is GnuCOBOL and not z/OS, so these captures are labeled as such in their manifest.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
out="$root/testdata/captures/CBACT04C"
rm -rf "$out"; mkdir -p "$out"
docker run --rm -i -v "$root/.cache/corpora/carddemo/app:/carddemo:ro" -v "$root/verify/src/main/resources/verify:/verify:ro" -v "$out:/out" ubuntu:24.04 bash -s <<'INNER'
set -euo pipefail
apt-get update -qq >/dev/null </dev/null; apt-get install -y -qq gnucobol3 python3 >/dev/null 2>&1 </dev/null
cobc --version | head -1
W=/tmp/w; mkdir -p $W; cd $W
OPTS="-std=ibm -fsign=EBCDIC -I /carddemo/cpy"
cat > CEE3ABD.cbl <<'C'
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CEE3ABD.
       PROCEDURE DIVISION.
           DISPLAY 'ABEND'.
           MOVE 99 TO RETURN-CODE.
           STOP RUN.
C
cobc $OPTS -m CEE3ABD.cbl
cobc $OPTS -m /carddemo/cbl/CBACT04C.cbl
cobc $OPTS -x -o TKDRIVER /verify/TKDRIVER.cbl
# A loader and an unloader: the program reads indexed files, and its inputs and outputs are saved as fixed records.
loader() { # name recsize keypos keylen altkey
  cat > L$1.cbl <<C
       IDENTIFICATION DIVISION.
       PROGRAM-ID. L$1.
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT I ASSIGN TO INFILE ORGANIZATION IS SEQUENTIAL.
           SELECT O ASSIGN TO OUTFILE ORGANIZATION IS INDEXED ACCESS IS SEQUENTIAL RECORD KEY IS O-KEY $5.
       DATA DIVISION.
       FILE SECTION.
       FD I.
       01 I-REC PIC X($2).
       FD O.
       01 O-REC.
          05 O-KEY PIC X($4).
          05 FILLER PIC X($(($2-$4))).
       WORKING-STORAGE SECTION.
       01 EOF PIC X VALUE 'N'.
       PROCEDURE DIVISION.
           OPEN INPUT I OUTPUT O.
           PERFORM UNTIL EOF = 'Y'
              READ I AT END MOVE 'Y' TO EOF
                 NOT AT END WRITE O-REC FROM I-REC
              END-READ
           END-PERFORM.
           CLOSE I O.
           STOP RUN.
C
  cobc -x -free -std=ibm L$1.cbl
}
loader TCAT 50 1 17 ""
loader ACCT 300 1 11 ""
loader DISC 50 1 16 ""
# XREF has an alternate key at offset 25 that the program reads by.
cat > LXREF.cbl <<'C'
       IDENTIFICATION DIVISION.
       PROGRAM-ID. LXREF.
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT I ASSIGN TO INFILE ORGANIZATION IS SEQUENTIAL.
           SELECT O ASSIGN TO OUTFILE ORGANIZATION IS INDEXED ACCESS IS DYNAMIC RECORD KEY IS O-KEY ALTERNATE RECORD KEY IS O-ALT WITH DUPLICATES.
       DATA DIVISION.
       FILE SECTION.
       FD I.
       01 I-REC PIC X(50).
       FD O.
       01 O-REC.
          05 O-KEY PIC X(16).
          05 FILLER PIC X(9).
          05 O-ALT PIC X(11).
          05 FILLER PIC X(14).
       WORKING-STORAGE SECTION.
       01 EOF PIC X VALUE 'N'.
       PROCEDURE DIVISION.
           OPEN INPUT I OUTPUT O.
           PERFORM UNTIL EOF = 'Y'
              READ I AT END MOVE 'Y' TO EOF
                 NOT AT END WRITE O-REC FROM I-REC
              END-READ
           END-PERFORM.
           CLOSE I O.
           STOP RUN.
C
cobc -x -free -std=ibm LXREF.cbl
cat > UACCT.cbl <<'C'
       IDENTIFICATION DIVISION.
       PROGRAM-ID. UACCT.
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT I ASSIGN TO INFILE ORGANIZATION IS INDEXED ACCESS IS SEQUENTIAL RECORD KEY IS I-KEY.
           SELECT O ASSIGN TO OUTFILE ORGANIZATION IS SEQUENTIAL.
       DATA DIVISION.
       FILE SECTION.
       FD I.
       01 I-REC.
          05 I-KEY PIC X(11).
          05 FILLER PIC X(289).
       FD O.
       01 O-REC PIC X(300).
       WORKING-STORAGE SECTION.
       01 EOF PIC X VALUE 'N'.
       PROCEDURE DIVISION.
           OPEN INPUT I OUTPUT O.
           PERFORM UNTIL EOF = 'Y'
              READ I AT END MOVE 'Y' TO EOF
                 NOT AT END WRITE O-REC FROM I-REC
              END-READ
           END-PERFORM.
           CLOSE I O.
           STOP RUN.
C
cobc -x -free -std=ibm UACCT.cbl
# Record sizes as real COBOL computes them, to check the layout code against (ADR 0002).
cat > LEN.cbl <<'C'
       IDENTIFICATION DIVISION.
       PROGRAM-ID. LEN.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       COPY CVTRA01Y.
       COPY CVACT03Y.
       COPY CVTRA02Y.
       COPY CVACT01Y.
       COPY CVTRA05Y.
       COPY CVTRA06Y.
       COPY CVCUS01Y.
       01 W-PACK.
          05 A PIC S9(7)V99 COMP-3.
          05 B PIC S9(4) COMP.
          05 C PIC S9(9) COMP.
          05 D PIC S9(10) COMP.
          05 E PIC X(3) OCCURS 4 TIMES.
          05 F PIC S9(5) SIGN LEADING SEPARATE.
          05 G PIC 9(4) COMP-5.
       PROCEDURE DIVISION.
           DISPLAY 'TRAN-CAT-BAL-RECORD ' LENGTH OF TRAN-CAT-BAL-RECORD.
           DISPLAY 'CARD-XREF-RECORD ' LENGTH OF CARD-XREF-RECORD.
           DISPLAY 'DIS-GROUP-RECORD ' LENGTH OF DIS-GROUP-RECORD.
           DISPLAY 'ACCOUNT-RECORD ' LENGTH OF ACCOUNT-RECORD.
           DISPLAY 'TRAN-RECORD ' LENGTH OF TRAN-RECORD.
           DISPLAY 'DALYTRAN-RECORD ' LENGTH OF DALYTRAN-RECORD.
           DISPLAY 'CUSTOMER-RECORD ' LENGTH OF CUSTOMER-RECORD.
           DISPLAY 'W-PACK ' LENGTH OF W-PACK.
           STOP RUN.
C
cobc -x $OPTS LEN.cbl && ./LEN | tee /out/cobol-record-lengths.txt
cat > make.py <<'P'
import sys,random
# Real CardDemo records, with balances varied so the arithmetic has something to do. Overpunch: {ABCDEFGHI +, }JKLMNOPQR -.
POS="{ABCDEFGHI"; NEG="}JKLMNOPQR"
def signed(v,w):
    s=str(abs(v)).zfill(w); last=int(s[-1]); return s[:-1]+(NEG[last] if v<0 else POS[last])
def pad(l,n): return l.rstrip("\r\n").ljust(n)[:n]
scen=sys.argv[1]; r=random.Random({"run1":1,"run2":2,"run3":3}[scen])
tcat=[pad(l,50) for l in open("/carddemo/data/ASCII/tcatbal.txt",errors="replace")]
xref=[pad(l,50) for l in open("/carddemo/data/ASCII/cardxref.txt",errors="replace")]
acct=[pad(l,300) for l in open("/carddemo/data/ASCII/acctdata.txt",errors="replace")]
disc=[pad(l,50) for l in open("/carddemo/data/ASCII/discgrp.txt",errors="replace")]
if scen=="run2":
    # No group matches, so every account takes the DEFAULT fallback branch.
    acct=[a[:110]+"NOSUCHGRP ".ljust(10)+a[120:] for a in acct]
out=[]
for i,t in enumerate(tcat):
    if scen=="run1": bal=r.randint(-2500000,9000000)
    elif scen=="run2": bal=r.randint(0,200000)
    else: bal=r.choice([1,7,99,100,119,120,1199,1200,1201,-1,-99,-1201,12345678])  # Around the truncation edges.
    out.append(t[:17]+signed(bal,11)+t[28:])
open("TCAT.in","w",newline="").write("".join(out)); open("XREF.in","w",newline="").write("".join(xref))
open("ACCT.in","w",newline="").write("".join(acct)); open("DISC.in","w",newline="").write("".join(disc))
print(scen,len(out),len(xref),len(acct),len(disc))
P
for scen in run1 run2 run3; do
  rm -f *.in TCATBALF XREFFILE ACCTFILE DISCGRP TRANSACT TKOUT TKIN *.dd
  python3 make.py $scen
  for f in TCAT:LTCAT:TCATBALF ACCT:LACCT:ACCTFILE DISC:LDISC:DISCGRP XREF:LXREF:XREFFILE; do IFS=: read n p dd <<<"$f"; DD_INFILE=$n.in DD_OUTFILE=$dd ./$p </dev/null; done
  # The PARM area: a big-endian length, then the date, in a variable record with GnuCOBOL's 4 byte record header.
  python3 -c "area=bytes([0,10])+b'2022071800'; open('TKIN','wb').write(bytes([0,len(area),0,0])+area)"
  COB_LIBRARY_PATH=. DD_TKIN=TKIN DD_TKOUT=TKOUT ./TKDRIVER CBACT04C > run.log 2>&1 </dev/null && rc=0 || rc=$?
  DD_INFILE=ACCTFILE DD_OUTFILE=ACCT.out ./UACCT </dev/null
  d=/out/$scen; mkdir -p $d
  cp TCAT.in $d/TCATBALF.input; cp XREF.in $d/XREFFILE.input; cp ACCT.in $d/ACCTFILE.input; cp DISC.in $d/DISCGRP.input; cp TKIN $d/TKIN.input
  cp ACCT.out $d/ACCTFILE.output; cp TRANSACT $d/TRANSACT.output; cp TKOUT $d/TKOUT.output; cp run.log $d/SYSOUT.txt
  echo "$scen rc=$rc tran_bytes=$(stat -c %s TRANSACT) acct_bytes=$(stat -c %s ACCT.out)"; tail -2 run.log
done
cat > /out/manifest.json <<J
{"program":"CBACT04C","source":"gnucobol","runtime":"$(cobc --version | head -1)","cobc_options":"$OPTS","note":"Executed under GnuCOBOL in Docker, not on z/OS. Sign and binary conventions set to match IBM (-std=ibm -fsign=EBCDIC). Character data is ASCII, not EBCDIC.","parm":"2022071800","records":{"TCATBALF":{"copybook":"CVTRA01Y","record":"TRAN-CAT-BAL-RECORD","length":50},"XREFFILE":{"copybook":"CVACT03Y","record":"CARD-XREF-RECORD","length":50},"ACCTFILE":{"copybook":"CVACT01Y","record":"ACCOUNT-RECORD","length":300},"DISCGRP":{"copybook":"CVTRA02Y","record":"DIS-GROUP-RECORD","length":50},"TRANSACT":{"copybook":"CVTRA05Y","record":"TRAN-RECORD","length":350}},"inputs":["TCATBALF","XREFFILE","ACCTFILE","DISCGRP"],"outputs":["ACCTFILE","TRANSACT"]}
J
INNER
