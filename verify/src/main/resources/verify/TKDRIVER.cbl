      * TKDRIVER: calls a program under test with a parameter area read
      * from a dataset, then writes the area back, so a callable
      * subprogram can be captured with no JCL of its own.
      * PARM or argument: the program to call.
      * TKIN: the area on entry. TKOUT: the area on return.
       IDENTIFICATION DIVISION.
       PROGRAM-ID. TKDRIVER.
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT TKIN-FILE ASSIGN TO TKIN
               ORGANIZATION IS SEQUENTIAL
               FILE STATUS IS WS-IN-STATUS.
           SELECT TKOUT-FILE ASSIGN TO TKOUT
               ORGANIZATION IS SEQUENTIAL
               FILE STATUS IS WS-OUT-STATUS.
       DATA DIVISION.
       FILE SECTION.
       FD  TKIN-FILE
           RECORD IS VARYING IN SIZE FROM 1 TO 32000
           DEPENDING ON WS-AREA-LEN.
       01  TKIN-REC                PIC X(32000).
       FD  TKOUT-FILE
           RECORD IS VARYING IN SIZE FROM 1 TO 32000
           DEPENDING ON WS-AREA-LEN.
       01  TKOUT-REC               PIC X(32000).
       WORKING-STORAGE SECTION.
       01  WS-IN-STATUS            PIC XX.
       01  WS-OUT-STATUS           PIC XX.
       01  WS-AREA-LEN             PIC 9(5) COMP VALUE 32000.
       01  WS-PROGRAM              PIC X(8).
       01  WS-AREA                 PIC X(32000).
       PROCEDURE DIVISION.
       0000-MAIN.
           ACCEPT WS-PROGRAM FROM COMMAND-LINE.
           OPEN INPUT TKIN-FILE.
           IF WS-IN-STATUS NOT = '00'
              DISPLAY 'TKDRIVER: cannot open TKIN ' WS-IN-STATUS
              MOVE 16 TO RETURN-CODE
              STOP RUN
           END-IF.
           READ TKIN-FILE INTO WS-AREA.
           CLOSE TKIN-FILE.
           CALL WS-PROGRAM USING WS-AREA
              ON EXCEPTION
                 DISPLAY 'TKDRIVER: cannot call ' WS-PROGRAM
                 MOVE 16 TO RETURN-CODE
                 STOP RUN
           END-CALL.
           OPEN OUTPUT TKOUT-FILE.
           WRITE TKOUT-REC FROM WS-AREA.
           CLOSE TKOUT-FILE.
           STOP RUN.
