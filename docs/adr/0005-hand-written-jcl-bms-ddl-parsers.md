# 0005: Hand-written JCL, BMS, DDL and CSD parsers instead of ANTLR grammars

Status: accepted. Date: 2026-11-01.

The spec names hand-written ANTLR grammars for JCL, BMS and DDL. All three are line and column structured: continuation depends on column 72 or a trailing comma, JCL meaning depends on statement position, in-stream data suspends the syntax, and symbol substitution happens before parameters mean anything. A context-free grammar would still need a hand-written pre-pass for all of that, leaving little for ANTLR to do.

Decision: small hand-written readers in `parsers/.../jcl`, `bms`, `ddl` and `csd`, each with one fixture per hard case from Module 1. ANTLR stays for COBOL through ProLeap. CSD parsing is an addition to the spec: without `DEFINE TRANSACTION` there is no transaction-to-program link and online programs would all look dead.
