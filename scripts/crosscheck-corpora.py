#!/usr/bin/env python3
"""
Independent check: counts statements in the raw source with no parser involved, then compares with what discovery stored.
Runs after validate-corpora.py. A difference is printed with the files it is in, because each one needs explaining.
"""
import json, os, pathlib, re, subprocess, sys
root = pathlib.Path(__file__).resolve().parent.parent
corp = root / ".cache/corpora"
def q(db, s):
    return subprocess.run(["docker", "exec", "toolkit-perf-pg", "psql", "-U", "toolkit", "-d", db, "-At", "-F", "|", "-c", s], capture_output=True, text=True).stdout.strip()
def code_lines(p):
    out = []
    for i, l in enumerate(open(p, errors="replace"), 1):
        l = l.rstrip("\r\n")
        if l[:1] in "*/" and l[:1]: continue
        c = l[:72] if len(l) > 72 else l
        if len(c) > 6 and c[6] in "*/$dD": continue
        if c.lstrip().startswith("*>"): continue
        out.append((i, c[7:] if len(c) > 7 else ""))
    return out
LIT = re.compile(r"'[^']*'|\"[^\"]*\"")
res = {}
for n in sys.argv[1:] or ["carddemo", "bankdemo", "bank-of-z", "cbsa", "genapp", "dsf", "taxe-fonciere", "bricks", "cobol-unit-test", "cobol-course"]:
    db = "v_" + n.replace("-", "_")
    rows = q(db, "select path from member where type='cobol' and parse_status='parsed' order by 1").split("\n")
    diffs = []
    tot = {k: [0, 0] for k in ("cics", "sql", "paragraphless_call", "program")}
    for path in rows:
        if not path: continue
        lines = code_lines(corp / n / path)
        stripped = [(i, LIT.sub("''", t)) for i, t in lines]
        cics = {i for i, t in stripped if re.search(r"(?i)\bEXEC\s+CICS\b", t)}
        # A lone EXEC on one line and CICS on the next is also a statement.
        for k in range(len(stripped) - 1):
            if re.search(r"(?i)\bEXEC\s*$", stripped[k][1]) and re.match(r"(?i)\s*CICS\b", stripped[k + 1][1]): cics.add(stripped[k][0])
        sql = set()
        for k, (i, t) in enumerate(stripped):
            if re.search(r"(?i)\bEXEC\s+SQL\b", t):
                # Up to END-EXEC, however many lines: INCLUDE expands and DECLARE ... TABLE is a declaration, not a statement.
                body = ""
                for _, x in stripped[k:k + 40]:
                    body += " " + x
                    if re.search(r"(?i)END-EXEC", x): break
                if not re.search(r"(?i)\bINCLUDE\b|\bDECLARE\s+\S+\s+TABLE\b", body): sql.add(i)
        call = {i for i, t in stripped if re.search(r"(?i)(?<![\w-])CALL\s+(''|[A-Z0-9])", t)}
        progs = {i for i, t in stripped if re.search(r"(?i)^\s*PROGRAM-ID\b", t)}
        esc = path.replace("'", "''")
        db_cics = {int(x) for x in q(db, f"select distinct c.line from cics_cmd c join member m on m.id=c.member_id where m.path='{esc}'").split("\n") if x}
        db_sql = {int(x) for x in q(db, f"select distinct s.line from sql_stmt s join member m on m.id=s.member_id where m.path='{esc}' and s.stmt_type<>'DECLARE TABLE'").split("\n") if x}
        db_call = {int(x) for x in q(db, f"select distinct e.line from graph_edge e join member m on m.id=e.member_id where m.path='{esc}' and e.type='CALLS' union select distinct u.line from unresolved_call u join member m on m.id=u.member_id where m.path='{esc}' and u.reason like 'CALL%'").split("\n") if x}
        fallback = q(db, f"select count(*) from dialect_feature d join member m on m.id=d.member_id where m.path='{esc}' and d.feature like 'FALLBACK%'") != "0"
        for name, raw, got in (("cics", cics, db_cics), ("sql", sql, db_sql), ("paragraphless_call", call, db_call)):
            tot[name][0] += len(raw); tot[name][1] += len(raw & got)
            miss = sorted(raw - got)
            if miss: diffs.append(f"{name} {path} missing lines {miss[:6]}{' fallback' if fallback else ''}")
    res[n] = dict(members=len([r for r in rows if r]), cics=tot["cics"], sql=tot["sql"], call=tot["paragraphless_call"], differences=diffs)
    print(n, "cobol", res[n]["members"], "| EXEC CICS raw/found", tot["cics"], "| EXEC SQL", tot["sql"], "| CALL", tot["paragraphless_call"])
    for d in diffs[:10]: print("   ", d[:150])
json.dump(res, open(root / ".cache/validation/crosscheck.json", "w"), indent=1)
