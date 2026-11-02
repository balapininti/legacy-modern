#!/usr/bin/env python3
"""Drives the real MCP server over stdio against each validated repo, calling tools on that repo's real data."""
import json, os, pathlib, subprocess, sys, threading
root = pathlib.Path(__file__).resolve().parent.parent
jar = os.environ.get("TOOLKIT_JAR", str(root / "cli/target/toolkit.jar"))
class Mcp:
    def __init__(self, db):
        env = dict(os.environ, TOOLKIT_DB_URL=f"jdbc:postgresql://localhost:55433/{db}", TOOLKIT_DB_USER="toolkit", TOOLKIT_DB_PASSWORD="toolkit", TOOLKIT_USER="probe")
        self.p = subprocess.Popen(["java", "-jar", jar, "mcp", "stdio"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, env=env, cwd=str(root / ".cache"))
        self.n = 0
        self.rpc("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "probe", "version": "1"}})
        self.p.stdin.write(json.dumps({"jsonrpc": "2.0", "method": "notifications/initialized"}) + "\n"); self.p.stdin.flush()
    def rpc(self, m, params):
        self.n += 1
        self.p.stdin.write(json.dumps({"jsonrpc": "2.0", "id": self.n, "method": m, "params": params}) + "\n"); self.p.stdin.flush()
        while True:
            line = self.p.stdout.readline()
            if not line: raise RuntimeError("server closed")
            d = json.loads(line)
            if d.get("id") == self.n: return d
    def tool(self, tool_name, **args):
        r = self.rpc("tools/call", {"name": tool_name, "arguments": args})["result"]
        return r.get("isError", False), json.loads(r["content"][0]["text"])
    def close(self):
        self.p.terminate()
def sql(db, s):
    return subprocess.run(["docker", "exec", "toolkit-perf-pg", "psql", "-U", "toolkit", "-d", db, "-At", "-F", "|", "-c", s], capture_output=True, text=True).stdout.strip()
out = {}
for n in sys.argv[1:] or ["carddemo", "bankdemo", "bank-of-z", "cbsa", "genapp", "dsf", "taxe-fonciere", "bricks", "cobol-unit-test", "cobol-course"]:
    db = "v_" + n.replace("-", "_"); r = {}; m = Mcp(db)
    try:
        r["tools"] = len(m.rpc("tools/list", {})["result"]["tools"])
        e, x = m.tool("inventory_summary"); r["inventory"] = not e
        pgm = sql(db, "select d.name from graph_edge e join graph_node d on d.id=e.dst_id where e.type in ('CALLS','LINKS','XCTLS') and exists (select 1 from program p where upper(p.program_id)=d.name) group by 1 order by count(*) desc limit 1") or sql(db, "select program_id from program limit 1")
        if pgm:
            e, x = m.tool("who_calls", program=pgm, depth=2); r["who_calls"] = (pgm, 0 if e else x["total_items"])
            sample = None if e or not x["items"] else x["items"][0]
            if sample:
                # The citation must point at a real line that names the callee.
                e2, s = m.tool("get_source", member=sample["member"], line_start=sample["line"], line_end=sample["line"]+6)
                text = " ".join(i["text"] for i in s.get("items", [])).upper()
                # Named on the line, or the statement goes through a variable and that member sets it to the name as a literal.
                callee = sample["to_program"].split(".")[-1]
                whole = (root / ".cache/corpora" / n / sample["member"]).read_text(errors="replace").upper()
                r["citation_ok"] = callee in text or f"'{callee}'" in whole or f'"{callee}"' in whole; r["citation"] = (sample["member"], sample["line"], sample["to_program"])
            e, x = m.tool("get_program", name=pgm, include=["callers", "callees", "tables", "jcl", "maps"]); r["get_program"] = not e
        tbl = sql(db, "select d.name from graph_edge e join graph_node d on d.id=e.dst_id where d.kind='table' group by 1 order by count(*) desc limit 1")
        if tbl:
            e, x = m.tool("table_usage", table=tbl); r["table_usage"] = (tbl, 0 if e else x["total_items"])
        cb = sql(db, "select name from member where type='copybook' and parse_status='parsed' and id in (select owner_member_id from data_item where program_id is null) order by length(name) desc limit 1")
        if cb:
            e, x = m.tool("get_copybook", name=cb)
            if e and x["error"]["code"] == "AMBIGUOUS": e, x = m.tool("get_copybook", name=sql(db, f"select path from member where name='{cb}' and type='copybook' limit 1"))
            r["get_copybook"] = (cb, 0 if e else x["total_items"])
        job = sql(db, "select name from jcl_job order by id limit 1")
        if job:
            e, x = m.tool("job_flow", job=job); r["job_flow"] = (job, 0 if e else x["total_items"])
        mp = sql(db, "select name from bms_map limit 1")
        if mp:
            e, x = m.tool("screen_flow", map=mp); r["screen_flow"] = (mp, 0 if e else len(x["items"][0].get("fields", [])))
        e, x = m.tool("search", query="error handling", limit=3); r["search_hits"] = 0 if e else x["total_items"]
    except Exception as ex:
        r["exception"] = str(ex)
    finally:
        m.close()
    out[n] = r; print(n, json.dumps(r)[:260]); sys.stdout.flush()
json.dump(out, open(root / ".cache/validation/mcp-probe.json", "w"), indent=1)
