#!/usr/bin/env python3
"""End-to-end proof of the Windows bundle on a real Windows machine (the GitHub windows-latest runner), as a client
would use it: unzip into a folder whose path has a space, set up against CardDemo checked out with CRLF line ends,
start, check, discover, index, query MCP over HTTPS, stop, restart, run as a Windows service, delete the folder.

    python scripts\\windows-smoke.py --zip target\\toolkit-<version>-windows-x64.zip [--artifacts smoke-logs]

Python 3.9+, standard library only. Windows only. The service section and --hide-vcruntime need an administrator
(the CI runner is one). Prints a PASS/FAIL table and exits 1 when any check failed. Logs of the install and of every
command run are copied to --artifacts (never config\\toolkit.env, which holds secrets).
"""
import argparse
import base64
import hashlib
import json
import os
import pathlib
import re
import shutil
import socket
import ssl
import subprocess
import sys
import tempfile
import time
import traceback
import urllib.error
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
CARDDEMO_URL = "https://github.com/aws-samples/aws-mainframe-modernization-carddemo"


def carddemo_pin():
    """The commit scripts/fetch-corpora.sh pins, which testdata/snapshots/carddemo-summary.json was made from."""
    m = re.search(r"^fetch carddemo \S+ ([0-9a-f]{40})", (ROOT / "scripts" / "fetch-corpora.sh").read_text(), re.M)
    if not m:
        raise SystemExit("no carddemo pin in scripts/fetch-corpora.sh")
    return m.group(1)


class Smoke:
    def __init__(self, a):
        self.a = a
        self.install = pathlib.Path(a.install)
        self.corpus = pathlib.Path(a.corpus)
        self.artifacts = pathlib.Path(a.artifacts)
        self.artifacts.mkdir(parents=True, exist_ok=True)
        self.cmdlog = self.artifacts / "commands.log"
        self.results = []
        self.token = None
        self.members = None
        self.env = {}
        # The client's shell has none of our variables; a developer's TOOLKIT_* must not leak in either.
        self.base_env = {k: v for k, v in os.environ.items() if not k.upper().startswith("TOOLKIT_")}

    # ---- bookkeeping ----
    def record(self, name, ok, detail=""):
        self.results.append((name, ok, detail))
        print(f"[{'PASS' if ok else 'FAIL'}] {name}{': ' + detail if detail else ''}", flush=True)
        return ok

    def check(self, name, fn, fatal=False):
        t0 = time.time()
        try:
            detail = fn()
            ok = True
        except Exception as e:  # noqa: BLE001 - every failure becomes a row in the table
            ok, detail = False, f"{type(e).__name__}: {e}"
            with open(self.cmdlog, "a", encoding="utf-8") as f:
                f.write(f"\n### {name} failed\n{traceback.format_exc()}\n")
        self.record(name, ok, f"{detail or ''} ({time.time() - t0:.0f} s)".strip())
        if not ok and fatal:
            raise Abort(name)
        return ok

    # ---- running things ----
    def run(self, argv, timeout=900, env=None, cwd=None, script=None, log_output=True):
        """Runs a program with its output in a file, not a pipe: the toolkit's background processes inherit handles,
        and a pipe would stay open until they exit. A .cmd goes through cmd /s /c, as typed in a console."""
        out = tempfile.NamedTemporaryFile(delete=False, suffix=".out", dir=self.artifacts)
        out.close()
        if script:
            line = f'cmd.exe /d /s /c ""{script}" {subprocess.list2cmdline(argv)}"'
        else:
            line = subprocess.list2cmdline(argv)
        with open(out.name, "wb") as f:
            try:
                rc = subprocess.run(line, stdin=subprocess.DEVNULL, stdout=f, stderr=subprocess.STDOUT, timeout=timeout,
                                    env=env or self.base_env, cwd=cwd or str(self.install.parent)).returncode
            except subprocess.TimeoutExpired:
                rc = "timeout"
        text = pathlib.Path(out.name).read_bytes().decode("utf-8", "replace")
        os.unlink(out.name)
        with open(self.cmdlog, "a", encoding="utf-8") as f:
            f.write(f"\n$ {self.redact(line)}\n-> {rc}\n{self.redact(text) if log_output else '(output not logged: it holds a secret)'}\n")
        return rc, text

    def redact(self, text):
        """The command log is uploaded from a public repository's CI: no password or token goes into it."""
        text = re.sub(r"(admin / )\S+", r"\1<redacted>", text)
        for k, v in self.env.items():
            if ("PASSWORD" in k or "KEY" in k) and len(v) >= 8:
                text = text.replace(v, "<" + k + ">")
        if self.token:
            text = text.replace(self.token, "<token>")
        return text

    def tk(self, *args, script="toolkit.cmd", timeout=900, ok=True):
        rc, out = self.run(list(args), timeout=timeout, script=str(self.install / script))
        if ok and rc != 0:
            raise AssertionError(f"{script} {' '.join(args)} exited {rc}:\n{tail(out)}")
        return rc, out

    def service(self, *args, timeout=300):
        return self.run(list(args), timeout=timeout, script=str(self.install / "service" / "service.cmd"))

    def read_env(self):
        env = {}
        for line in (self.install / "config" / "toolkit.env").read_text(encoding="utf-8-sig").splitlines():
            if line.strip() and not line.lstrip().startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                env[k.strip()] = v
        self.env = env
        return env

    @property
    def https(self):
        return int(self.env.get("TOOLKIT_HTTPS_PORT", "8443"))

    @property
    def pg_port(self):
        return int(self.env.get("TOOLKIT_PG_PORT", "55432"))

    def ssl_context(self):
        # Strict: hostname and chain verified against the certificate setup exported, as a client is told to do.
        return ssl.create_default_context(cafile=str(self.install / "data" / "tls" / "toolkit.crt"))

    def http(self, path, data=None, headers=None, method=None, timeout=60):
        req = urllib.request.Request(f"https://localhost:{self.https}{path}", data=data, headers=headers or {}, method=method)
        try:
            with urllib.request.urlopen(req, context=self.ssl_context(), timeout=timeout) as r:
                return r.status, dict(r.headers), r.read()
        except urllib.error.HTTPError as e:
            return e.code, dict(e.headers), e.read()

    def wait_health(self, seconds=240):
        deadline = time.time() + seconds
        last = None
        while time.time() < deadline:
            try:
                code, _, body = self.http("/actuator/health", timeout=5)
                if code == 200:
                    return json.loads(body)
                last = f"HTTP {code}"
            except Exception as e:  # noqa: BLE001
                last = repr(e)
            time.sleep(2)
        raise AssertionError(f"no healthy answer on https://localhost:{self.https}/actuator/health in {seconds} s: {last}")

    def psql(self, sql, path_extra=True):
        env = dict(self.base_env, PGPASSWORD=self.env["TOOLKIT_DB_PASSWORD"], PGCONNECT_TIMEOUT="10")
        if path_extra:  # as the launcher runs PostgreSQL programs: the JRE's VC++ runtime as a fallback
            env["PATH"] = env.get("PATH", "") + os.pathsep + str(self.install / "runtime" / "jre" / "bin")
        rc, out = self.run([str(self.install / "runtime" / "pgsql" / "bin" / "psql.exe"), "-h", "127.0.0.1", "-p", str(self.pg_port),
                            "-U", self.env.get("TOOLKIT_DB_USER", "toolkit"), "-d", "toolkit", "-X", "-q", "-At", "-v", "ON_ERROR_STOP=1",
                            "-c", sql], env=env, timeout=120)
        if rc != 0:
            raise AssertionError(f"psql exited {rc}: {tail(out)}")
        return out.strip()

    # ---- MCP over HTTPS (streamable HTTP) ----
    def mcp(self, method, params=None, token=None, session=None, notify=False):
        body = {"jsonrpc": "2.0", "method": method, "params": params or {}}
        if not notify:
            body["id"] = int(time.time() * 1000) % 1000000000
        headers = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream"}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        if session:
            headers["Mcp-Session-Id"] = session
        code, h, raw = self.http("/mcp", data=json.dumps(body).encode(), headers=headers, method="POST", timeout=180)
        sid = next((v for k, v in h.items() if k.lower() == "mcp-session-id"), session)
        if notify or code >= 400:
            return code, None, sid
        text = raw.decode("utf-8", "replace")
        ctype = next((v for k, v in h.items() if k.lower() == "content-type"), "")
        if "text/event-stream" in ctype:
            for event in re.split(r"\r?\n\r?\n", text):
                data = "\n".join(l[5:].lstrip() for l in event.splitlines() if l.startswith("data:"))
                if data:
                    msg = json.loads(data)
                    if msg.get("id") == body["id"]:
                        return code, msg, sid
            raise AssertionError(f"no response with id {body['id']} in the event stream: {text[:500]}")
        return code, json.loads(text), sid

    def mcp_session(self, token):
        code, init, sid = self.mcp("initialize", {"protocolVersion": "2025-06-18", "capabilities": {},
                                                  "clientInfo": {"name": "windows-smoke", "version": "1"}}, token=token)
        if code != 200 or "result" not in (init or {}):
            raise AssertionError(f"initialize: HTTP {code} {init}")
        self.mcp("notifications/initialized", token=token, session=sid, notify=True)
        return sid, init["result"]

    def tool(self, token, sid, name, **args):
        code, r, _ = self.mcp("tools/call", {"name": name, "arguments": args}, token=token, session=sid)
        if code != 200 or "result" not in (r or {}):
            raise AssertionError(f"tools/call {name}: HTTP {code} {r}")
        res = r["result"]
        return res.get("isError", False), json.loads(res["content"][0]["text"])

    def tools_count(self, token):
        sid, _ = self.mcp_session(token)
        code, r, _ = self.mcp("tools/list", token=token, session=sid)
        return len(r["result"]["tools"])

    # ---- processes and ports ----
    def our_processes(self):
        ps = ("Get-CimInstance Win32_Process | Select-Object ProcessId,Name,ExecutablePath,CommandLine | ConvertTo-Json -Compress")
        rc, out = self.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", ps], timeout=120)
        procs = json.loads(out[out.index("["):]) if "[" in out else []
        root = str(self.install).lower()
        return [p for p in procs if root in (p.get("ExecutablePath") or "").lower() or root in (p.get("CommandLine") or "").lower()]

    def assert_nothing_runs(self):
        deadline = time.time() + 60
        while True:
            left = [f"{p['ProcessId']} {p['Name']}" for p in self.our_processes()
                    if (p.get("Name") or "").lower() in ("java.exe", "javaw.exe", "postgres.exe", "pg_ctl.exe")]
            busy = [p for p in (self.https, self.pg_port) if port_open(p)]
            if not left and not busy:
                return f"no java/postgres from the install; ports {self.https} and {self.pg_port} free"
            if time.time() > deadline:
                raise AssertionError(f"still running: {left}; ports in use: {busy}")
            time.sleep(2)

    # ---- the checks ----
    def unzip(self):
        if self.install.exists():
            shutil.rmtree(self.install)
        # A plain folder, as Explorer's Extract All makes, inheriting the profile's ACL (mkdtemp's is owner-only).
        tmp = self.install.parent / f"unzip-{os.getpid()}"
        tmp.mkdir()
        with zipfile.ZipFile(self.a.zip) as z:
            z.extractall(tmp)
        (tmp / "toolkit").rename(self.install)
        tmp.rmdir()
        for p in ("toolkit.cmd", "start.cmd", "stop.cmd", "INSTALL.md", "USING.md", "skills/legacy-modernize/SKILL.md",
                  "skills/legacy-modernize/templates.md", "sbom.json", "LICENSES/INVENTORY.md", "lib/toolkit.jar", "ui/index.html",
                  "config/toolkit.yaml.example", "runtime/jre/bin/java.exe", "runtime/pgsql/bin/postgres.exe", "runtime/node/node.exe",
                  "tools/ng-extract/extract.js", "tools/ng-extract/node_modules/typescript/package.json", "service/toolkit.exe", "service/toolkit.xml",
                  "service/service.cmd"):
            if not (self.install / p).is_file():
                raise AssertionError(f"missing {p}")
        if self.a.expect_vector and not (self.install / "runtime/pgsql/lib/vector.dll").is_file():
            raise AssertionError("no runtime/pgsql/lib/vector.dll")
        if b"\r\n" not in (self.install / "toolkit.cmd").read_bytes():
            raise AssertionError("toolkit.cmd has no CRLF line ends")
        return f"{self.install} ({sum(1 for _ in self.install.rglob('*'))} entries)"

    def checkout(self):
        if self.corpus.exists():
            shutil.rmtree(self.corpus, onerror=force_remove)
        self.corpus.mkdir(parents=True)
        pin = carddemo_pin()
        g = ["git", "-c", "core.autocrlf=true", "-C", str(self.corpus)]
        for cmd in (["init", "-q"], ["remote", "add", "origin", CARDDEMO_URL], ["fetch", "-q", "--depth", "1", "origin", pin], ["checkout", "-q", "FETCH_HEAD"]):
            rc, out = self.run(g + cmd, timeout=600)
            if rc != 0:
                raise AssertionError(f"git {' '.join(cmd)}: {tail(out)}")
        cbl = sorted((self.corpus / "app" / "cbl").glob("*.cbl"))
        crlf = sum(1 for p in cbl if b"\r\n" in p.read_bytes())
        if not cbl or crlf != len(cbl):
            raise AssertionError(f"{crlf} of {len(cbl)} COBOL files have CRLF line ends; the CRLF checkout is the point")
        return f"CardDemo {pin[:12]} at {self.corpus}, CRLF in all {len(cbl)} .cbl files"

    def dll_report(self):
        """Where each DLL the PostgreSQL programs import comes from, with this machine's PATH plus runtime\\jre\\bin."""
        dirs = self.base_env.get("PATH", "").split(os.pathsep) + [str(self.install / "runtime" / "jre" / "bin")]
        lines = []
        for exe in ("initdb.exe", "postgres.exe", "pg_ctl.exe", "psql.exe"):
            r = unresolved_dlls(self.install / "runtime" / "pgsql" / "bin" / exe, dirs)
            missing = sorted(k for k, v in r.items() if v is None)
            outside = sorted(f"{k} <- {v}" for k, v in r.items() if v and str(self.install).lower() not in v.lower() and "\\windows\\" not in v.lower())
            lines.append(f"{exe}: missing {missing or 'none'}; from outside the install and Windows: {outside or 'none'}")
        sys32 = pathlib.Path(os.environ.get("SystemRoot", r"C:\Windows")) / "System32"
        lines.append("System32 has: " + ", ".join(n for n in ("vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll") if (sys32 / n).is_file()))
        return "\n".join(lines)

    def setup(self):
        try:
            print(self.dll_report(), flush=True)
        except Exception as e:  # noqa: BLE001
            print(f"dll report failed: {e}")
        rc, out = self.tk("setup", "--source", str(self.corpus), "--client", "carddemo", timeout=600)
        for want in ("admin / ", "start.cmd", "toolkit.crt"):
            if want not in out:
                raise AssertionError(f"setup output lacks {want!r}:\n{tail(out)}")
        self.read_env()
        acl = " ".join(self.run(["icacls", str(self.install / "config" / "toolkit.env")])[1].split()[1:-6])
        root = " ".join(self.run(["icacls", str(self.install)])[1].split()[3:-6])
        return f"config, secrets, certificate, database; toolkit.env ACL: {acl}; install folder ACL: {root}"

    def start(self):
        rc, out = self.run([], timeout=600, script=str(self.install / "start.cmd"))
        if rc != 0 or "toolkit is running" not in out:
            raise AssertionError(f"start.cmd exited {rc}:\n{tail(out)}")
        return out.strip().splitlines()[-3] if len(out.strip().splitlines()) >= 3 else out.strip()

    def health(self):
        h = self.wait_health()
        if h.get("status") != "UP":
            raise AssertionError(h)
        return f"https://localhost:{self.https}/actuator/health {h} (certificate verified with data\\tls\\toolkit.crt)"

    def doctor(self):
        rc, out = self.tk("doctor", ok=False, timeout=300)
        rows = {m.group(2): m.group(1) for m in re.finditer(r"^(PASS|WARN|FAIL|SKIP)\s+(\S+)", out, re.M)}
        want = {"database": "PASS", "source": "PASS", "vector": "PASS" if self.a.expect_vector else "WARN"}
        bad = {k: rows.get(k) for k, v in want.items() if rows.get(k) != v}
        if bad:
            raise AssertionError(f"doctor (exit {rc}): {bad}\n{tail(out)}")
        return ", ".join(f"{k} {v}" for k, v in sorted(rows.items()))

    def discover(self):
        report = self.install / "data" / "discovery-report.json"
        rc, out = self.tk("discover", "--report", str(report), timeout=1800)
        start = re.search(r"^\{\s*$", out, re.M)
        if not start:
            raise AssertionError(f"no summary in the output:\n{tail(out)}")
        summary, _ = json.JSONDecoder().raw_decode(out[start.start():])
        (self.artifacts / "carddemo-summary-windows.json").write_text(json.dumps(summary, indent=2, sort_keys=True), encoding="utf-8")
        snap = json.loads((ROOT / "testdata" / "snapshots" / "carddemo-summary.json").read_text(encoding="utf-8"))
        diff = {k: {"snapshot": v, "windows": summary.get(k)} for k, v in snap.items() if summary.get(k) != v}
        if diff:
            raise AssertionError("summary differs from testdata/snapshots/carddemo-summary.json: " + json.dumps(diff, sort_keys=True))
        if not report.is_file():
            raise AssertionError("no --report file")
        return f"{len(snap)} summary fields equal the LF snapshot ({summary.get('lines')} lines, {summary.get('programs')} programs)"

    def index(self):
        rc, out = self.tk("index", timeout=1800)
        return tail(out, 1)

    def paths(self):
        cols = self.psql("SELECT table_name || '.' || column_name FROM information_schema.columns WHERE table_schema = 'public'"
                         " AND data_type IN ('text', 'character varying') AND column_name ~ '(path|member|file)' ORDER BY 1").splitlines()
        if "member.path" not in cols:
            raise AssertionError(f"no member.path column among {cols}")
        found, checked = {}, 0
        for c in cols:
            t, col = c.split(".")
            n = int(self.psql(f'SELECT count(*) FROM "{t}" WHERE position(chr(92) in "{col}") > 0'))
            checked += int(self.psql(f'SELECT count("{col}") FROM "{t}"'))
            if n:
                found[c] = self.psql(f'SELECT "{col}" FROM "{t}" WHERE position(chr(92) in "{col}") > 0 LIMIT 3')
        self.members = int(self.psql("SELECT count(*) FROM member"))
        if found:
            raise AssertionError(f"stored paths with a backslash: {found}")
        sample = self.psql("SELECT path FROM member WHERE path LIKE 'app/cbl/%' ORDER BY path LIMIT 1")
        if not sample.startswith("app/cbl/"):
            raise AssertionError(f"unexpected member path {sample!r}")
        return f"{checked} values in {len(cols)} path columns, none with a backslash; {self.members} members, e.g. {sample}"

    def vector(self):
        v = self.psql("SELECT extversion FROM pg_extension WHERE extname = 'vector'")
        if self.a.expect_vector:
            if not v:
                raise AssertionError("extension vector is not installed in the toolkit database")
            d = self.psql("SELECT '[1,2,3]'::vector <-> '[1,2,4]'::vector")
            col = self.psql("SELECT count(*) FROM information_schema.columns WHERE table_name = 'chunk' AND column_name = 'embedding'")
            if d != "1" or col != "1":
                raise AssertionError(f"distance {d!r}, chunk.embedding columns {col!r}")
            return f"vector {v}, chunk.embedding present, distance works"
        if v:
            raise AssertionError(f"lexical bundle, but vector {v} is installed")
        return "lexical-only bundle: no vector extension, as expected"

    def issue_token(self):
        rc, out = self.run(["token", "--user", "smoke", "--role", "developer"], script=str(self.install / "toolkit.cmd"), log_output=False)
        self.token = out.strip().splitlines()[-1].strip() if out.strip() else ""
        if rc != 0:
            raise AssertionError(f"toolkit.cmd token exited {rc}")
        if not re.fullmatch(r"\S{20,}", self.token):
            raise AssertionError(f"no token in: {tail(out)}")
        return "developer token issued"

    def mcp_checks(self):
        sid, init = self.mcp_session(self.token)
        code, r, _ = self.mcp("tools/list", token=self.token, session=sid)
        n = len(r["result"]["tools"])
        if n != 19:
            raise AssertionError(f"tools/list returned {n} tools, not 19")
        pgm = self.psql("SELECT d.name FROM graph_edge e JOIN graph_node d ON d.id = e.dst_id WHERE e.type IN ('CALLS','LINKS','XCTLS')"
                        " AND EXISTS (SELECT 1 FROM program p WHERE upper(p.program_id) = d.name) GROUP BY 1 ORDER BY count(*) DESC, 1 LIMIT 1")
        err, x = self.tool(self.token, sid, "who_calls", program=pgm, depth=2)
        if err or not x.get("items"):
            raise AssertionError(f"who_calls {pgm}: {x}")
        cited = 0
        for it in x["items"]:
            member, line = it.get("member"), it.get("line")
            if not member or "\\" in member or not isinstance(line, int) or line < 1 or not (self.corpus / member).is_file():
                raise AssertionError(f"who_calls item without a usable member/line citation: {it}")
            src = (self.corpus / member).read_text(encoding="latin-1").upper()
            callee = (it.get("to_program") or pgm).split(".")[-1].upper()
            lines = src.splitlines()
            near = " ".join(lines[line - 1:line + 6]) if line <= len(lines) else ""
            if callee in near or f"'{callee}'" in src or f'"{callee}"' in src:
                cited += 1
        if cited == 0:
            raise AssertionError(f"no who_calls citation of {pgm} points at a line naming it: {x['items'][:3]}")
        first = x["items"][0]
        code401, _, _ = self.mcp("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "x", "version": "1"}})
        if code401 != 401:
            raise AssertionError(f"initialize without a token answered HTTP {code401}, not 401")
        server = init.get("serverInfo", {}).get("name")
        return (f"{server}: 19 tools; who_calls {pgm}: {x.get('total_items', len(x['items']))} callers, {cited} citations checked against the"
                f" source, e.g. {first['member']}:{first['line']}; 401 without a token")

    def ui(self):
        index = (self.install / "ui" / "index.html").read_bytes()
        out = []
        for path in ("/", "/review", "/programs/COSGN00C"):
            code, h, body = self.http(path, headers={"Accept": "text/html"})
            if code != 200 or body != index:
                raise AssertionError(f"GET {path}: HTTP {code}, {len(body)} bytes, index.html is {len(index)}")
            out.append(path)
        code, _, _ = self.http("/api/auth/me")
        if code != 401:
            raise AssertionError(f"/api/auth/me without a token: HTTP {code}, not 401")
        return f"{', '.join(out)} serve index.html without a token; /api 401"

    def stop(self):
        rc, out = self.run([], timeout=300, script=str(self.install / "stop.cmd"))
        if rc != 0:
            raise AssertionError(f"stop.cmd exited {rc}: {tail(out)}")
        return self.assert_nothing_runs()

    def restart(self):
        self.start()
        self.wait_health()
        n = self.tools_count(self.token)
        members = int(self.psql("SELECT count(*) FROM member"))
        if n != 19 or members != self.members:
            raise AssertionError(f"after restart: {n} tools, {members} members (before {self.members})")
        return f"data kept ({members} members); the token from before the restart still opens MCP"

    def cert_trust(self):
        """What INSTALL.md tells clients: certutil for the Windows store (VS Code, Cursor, browsers), NODE_EXTRA_CA_CERTS for Node (Claude Code)."""
        crt = self.install / "data" / "tls" / "toolkit.crt"
        der = base64.b64decode("".join(l for l in crt.read_text().splitlines() if l and not l.startswith("-----")))
        thumb = hashlib.sha1(der).hexdigest()
        notes = []
        rc, out = self.run(["certutil", "-user", "-addstore", "Root", str(crt)], timeout=30)
        user_ok = rc == 0
        notes.append(f"certutil -user -addstore Root: {'ok' if user_ok else 'exit ' + str(rc)}")
        machine = False
        if not user_ok:
            rc, out = self.run(["certutil", "-addstore", "Root", str(crt)], timeout=60)
            machine = rc == 0
            notes.append(f"certutil -addstore Root (machine, admin): {'ok' if machine else 'exit ' + str(rc)}")
        try:
            ps = (f"$r = Invoke-WebRequest -UseBasicParsing -Uri https://localhost:{self.https}/actuator/health; $r.StatusCode")
            rc, out = self.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", ps], timeout=120)
            if rc != 0 or "200" not in out:
                raise AssertionError(f"Windows (Schannel) does not trust the certificate after certutil: {tail(out)}")
            notes.append("Schannel trusts it")
        finally:
            self.run(["certutil", "-user", "-delstore", "Root", thumb], timeout=60)
            if machine:
                self.run(["certutil", "-delstore", "Root", thumb], timeout=60)
        node = str(self.install / "runtime" / "node" / "node.exe")
        js = f"fetch('https://localhost:{self.https}/actuator/health').then(r=>{{console.log('status',r.status);process.exit(r.status===200?0:1)}},e=>{{console.log(String(e.cause||e));process.exit(1)}})"
        rc0, out0 = self.run([node, "-e", js], timeout=60)
        rc, out = self.run([node, "-e", js], env=dict(self.base_env, NODE_EXTRA_CA_CERTS=str(crt)), timeout=60)
        if rc0 == 0 or rc != 0:
            raise AssertionError(f"node without NODE_EXTRA_CA_CERTS exit {rc0} ({out0.strip()}), with it exit {rc} ({out.strip()})")
        notes.append("Node rejects it without NODE_EXTRA_CA_CERTS and trusts it with")
        return "; ".join(notes)

    def hidden_vcruntime(self):
        """No VC++ Redistributable: the runner has it in System32, so hide it, prove the hiding works (a negative
        control fails with 0xC0000135), then prove the bundle still runs with only Temurin's copy in runtime\\jre\\bin."""
        sys32 = pathlib.Path(os.environ.get("SystemRoot", r"C:\Windows")) / "System32"
        names = ["vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll"]
        hidden = []
        jre_bin = self.install / "runtime" / "jre" / "bin"
        minimal = dict(self.base_env, PATH=f"{sys32};{sys32.parent}")
        # Not mkdtemp: since Python 3.12.4 it gives the folder an owner-only ACL, and the owner of what an elevated
        # process creates is Administrators, which PostgreSQL's restricted token cannot use.
        tmp = self.install / "data" / f"vc-check-{os.getpid()}"
        tmp.mkdir()
        try:
            for n in names:
                p = sys32 / n
                if p.exists():
                    self.run(["takeown", "/f", str(p)], timeout=60)
                    self.run(["icacls", str(p), "/grant", "*S-1-5-32-544:F"], timeout=60)
                    p.rename(p.with_name(n + ".hidden-by-smoke"))
                    hidden.append(p)
            initdb = str(self.install / "runtime" / "pgsql" / "bin" / "initdb.exe")
            (tmp / "pw").write_text("x\n")
            rc_neg, out_neg = self.run([initdb, "-D", str(tmp / "neg"), "-U", "x", "--pwfile", str(tmp / "pw"), "-A", "scram-sha-256"], env=minimal, cwd=str(tmp), timeout=300)
            if rc_neg != 0xC0000135 and rc_neg != -1073741515:
                raise AssertionError(f"negative control: initdb without any VC++ runtime should fail with 0xC0000135, got {rc_neg}: {tail(out_neg)}")
            with_jre = dict(minimal, PATH=minimal["PATH"] + os.pathsep + str(jre_bin))
            rc, out = self.run([initdb, "-D", str(tmp / "pos"), "-U", "x", "--pwfile", str(tmp / "pw"), "-A", "scram-sha-256", "-E", "UTF8", "--no-locale"],
                               env=with_jre, cwd=str(tmp), timeout=300)
            if rc != 0:
                raise AssertionError(f"initdb with runtime\\jre\\bin on PATH exited {rc}: {tail(out)}")
            # The launcher itself, with nothing on PATH but Windows: it adds runtime\jre\bin for PostgreSQL.
            rc, out = self.run([], timeout=600, env=minimal, script=str(self.install / "start.cmd"))
            if rc != 0:
                raise AssertionError(f"start.cmd without the VC++ Redistributable exited {rc}: {tail(out)}")
            self.wait_health()
            v = self.psql("SELECT '[1,2,3]'::vector <-> '[1,2,4]'::vector" if self.a.expect_vector else "SELECT 1")
            mods = ("Get-Process postgres -ErrorAction SilentlyContinue | ForEach-Object { $_.Modules } | Where-Object { $_.ModuleName -match '^(vcruntime140|msvcp140)' } "
                    "| Select-Object -ExpandProperty FileName | Sort-Object -Unique")
            rc, loaded = self.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", mods], timeout=120)
            loaded = [l.strip() for l in loaded.splitlines() if l.strip()]
            if not loaded or any(str(jre_bin).lower() not in l.lower() for l in loaded):
                raise AssertionError(f"postgres loaded the VC++ runtime from {loaded}, not from {jre_bin}")
            rc, out = self.run([], timeout=300, env=minimal, script=str(self.install / "stop.cmd"))
            if rc != 0:
                raise AssertionError(f"stop.cmd exited {rc}: {tail(out)}")
            self.assert_nothing_runs()
            return (f"System32 VC++ DLLs hidden; initdb alone fails 0xC0000135 (control); with Temurin's runtime: initdb, start.cmd,"
                    f" query {'vector distance ' + v if self.a.expect_vector else v}, stop.cmd all work; postgres loaded {', '.join(pathlib.Path(l).name for l in loaded)} from runtime\\jre\\bin")
        finally:
            for p in hidden:
                p.with_name(p.name + ".hidden-by-smoke").rename(p)
            shutil.rmtree(tmp, ignore_errors=True)

    def service_mode(self):
        rc, out = self.service("install")
        if rc != 0:
            raise AssertionError(f"service.cmd install exited {rc}: {tail(out)}")
        try:
            rc, out = self.service("start")
            if rc != 0:
                raise AssertionError(f"service.cmd start exited {rc}: {tail(out)}")
            self.wait_health(300)
            rc, status = self.service("status")
            if "Started" not in status:
                raise AssertionError(f"service.cmd status: {status.strip()}")
            n = self.tools_count(self.token)
            if n != 19:
                raise AssertionError(f"MCP through the service: {n} tools")
            procs = self.our_processes()
            users = self.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                              "Get-Process java,postgres -IncludeUserName -ErrorAction SilentlyContinue | Select-Object -ExpandProperty UserName -Unique"], timeout=120)[1]
            rc, out = self.service("stop")
            if rc != 0:
                raise AssertionError(f"service.cmd stop exited {rc}: {tail(out)}")
            self.assert_nothing_runs()
        finally:
            rc, out = self.service("uninstall")
        if rc != 0:
            raise AssertionError(f"service.cmd uninstall exited {rc}: {tail(out)}")
        rc, status = self.service("status")
        if "NonExistent" not in status:
            raise AssertionError(f"after uninstall, status says {status.strip()}")
        return f"install, start, health, 19 MCP tools ({len(procs)} processes, as {' '.join(users.split())}), stop, uninstall"

    def collect_logs(self):
        dest = self.artifacts / "install-logs"
        logs = self.install / "data" / "logs"
        if logs.is_dir():
            shutil.copytree(logs, dest, dirs_exist_ok=True)
        for extra in ("config/toolkit.yaml", "data/discovery-report.json"):
            p = self.install / extra
            if p.is_file():
                dest.mkdir(parents=True, exist_ok=True)
                shutil.copy2(p, dest / p.name)

    def delete(self):
        errors = []
        shutil.rmtree(self.install, onerror=lambda f, p, e: errors.append(f"{p}: {e[1]}"))
        if errors or self.install.exists():
            raise AssertionError(f"could not delete the install folder: {errors[:10]}")
        return f"{self.install} deleted, no locked files"

    def main(self):
        try:
            self.check("unzip into a path with a space", self.unzip, fatal=True)
            self.check("CardDemo checked out with CRLF", self.checkout, fatal=True)
            self.check("toolkit.cmd setup", self.setup, fatal=True)
            self.check("start.cmd", self.start, fatal=True)
            self.check("health over HTTPS", self.health, fatal=True)
            self.check("toolkit.cmd doctor", self.doctor)
            self.check("discover: summary equals the LF snapshot", self.discover)
            self.check("index", self.index)
            self.check("no backslash in stored paths", self.paths)
            self.check("vector extension", self.vector)
            self.check("token", self.issue_token, fatal=True)
            self.check("MCP over HTTPS", self.mcp_checks)
            self.check("UI deep links", self.ui)
            if not self.a.skip_cert_trust:
                self.check("client certificate trust", self.cert_trust)
            self.check("stop.cmd leaves nothing running", self.stop)
            self.check("start again keeps data and token", self.restart)
            self.check("stop.cmd again", self.stop)
            if self.a.hide_vcruntime:
                self.check("works without the VC++ Redistributable", self.hidden_vcruntime)
            if not self.a.skip_service:
                self.check("Windows service (WinSW)", self.service_mode)
        except Abort:
            pass
        finally:
            try:
                self.collect_logs()
            except Exception as e:  # noqa: BLE001
                print(f"could not collect logs: {e}")
            if self.install.exists():
                if any(not ok for _, ok, _ in self.results):
                    self.run([], timeout=300, script=str(self.install / "stop.cmd"))
                    self.service("uninstall")
                self.check("delete the install folder", self.delete)
        width = max(len(n) for n, _, _ in self.results)
        print("\n" + "=" * 100)
        print(f"{'check'.ljust(width)}  result  detail")
        print("-" * 100)
        for n, ok, d in self.results:
            print(f"{n.ljust(width)}  {'PASS' if ok else 'FAIL'}    {d}")
        print("=" * 100)
        failed = [n for n, ok, _ in self.results if not ok]
        print(f"{len(self.results) - len(failed)} passed, {len(failed)} failed")
        (self.artifacts / "smoke-results.json").write_text(json.dumps([{"check": n, "ok": ok, "detail": d} for n, ok, d in self.results], indent=1))
        return 1 if failed else 0


class Abort(Exception):
    pass


def pe_imports(data):
    """DLL names in the import and delay-import tables of a PE image (as scripts/build-windows-bundle.py reads them)."""
    import struct
    pe = struct.unpack_from("<I", data, 0x3C)[0]
    nsec = struct.unpack_from("<H", data, pe + 6)[0]
    optsz = struct.unpack_from("<H", data, pe + 20)[0]
    opt = pe + 24
    dirs = opt + (112 if struct.unpack_from("<H", data, opt)[0] == 0x20B else 96)
    secs = [struct.unpack_from("<IIII", data, opt + optsz + i * 40 + 8) for i in range(nsec)]

    def off(rva):
        for vsize, va, rawsize, raw in secs:
            if va <= rva < va + max(vsize, rawsize):
                return rva - va + raw
        raise ValueError(rva)
    names = []
    for index, size, name_at in ((1, 20, 12), (13, 32, 4)):
        rva = struct.unpack_from("<I", data, dirs + index * 8)[0]
        if not rva:
            continue
        o = off(rva)
        while struct.unpack_from("<I", data, o + name_at)[0]:
            n = off(struct.unpack_from("<I", data, o + name_at)[0])
            names.append(data[n:data.index(b"\0", n)].decode("ascii"))
            o += size
    return names


def unresolved_dlls(exe, path_dirs):
    """Walks exe's DLL imports as the Windows loader searches for them (its folder, System32, Windows, PATH):
    returns {dll: where it was found or None}. API sets (api-ms-*, ext-ms-*) are resolved by Windows itself."""
    sysroot = pathlib.Path(os.environ.get("SystemRoot", r"C:\Windows"))
    search = [pathlib.Path(exe).parent, sysroot / "System32", sysroot] + [pathlib.Path(d) for d in path_dirs if d]
    found, todo = {}, [pathlib.Path(exe)]
    while todo:
        img = todo.pop()
        try:
            names = pe_imports(img.read_bytes())
        except Exception:  # noqa: BLE001
            continue
        for n in names:
            k = n.lower()
            if k in found or k.startswith(("api-ms-", "ext-ms-")):
                continue
            hit = next((d / n for d in search if (d / n).is_file()), None)
            found[k] = str(hit) if hit else None
            if hit and sysroot not in hit.parents:
                todo.append(hit)
    return found


def tail(text, n=40):
    lines = text.strip().splitlines()
    return "\n".join(lines[-n:])


def port_open(port):
    with socket.socket() as s:
        s.settimeout(0.5)
        return s.connect_ex(("127.0.0.1", port)) == 0


def force_remove(func, path, _):
    os.chmod(path, 0o666)
    func(path)


def main():
    if os.name != "nt":
        raise SystemExit("windows-smoke.py runs on Windows only")
    home = pathlib.Path(os.environ.get("USERPROFILE", r"C:\Users\runneradmin"))
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--zip", required=True)
    ap.add_argument("--install", default=str(home / "Tool Kit"), help="install folder (default: %%USERPROFILE%%\\Tool Kit)")
    ap.add_argument("--corpus", default=str(home / "main frame src" / "carddemo"), help="where CardDemo is checked out")
    ap.add_argument("--artifacts", default="smoke-logs")
    ap.add_argument("--lexical", dest="expect_vector", action="store_false", help="the bundle has no pgvector")
    ap.add_argument("--skip-service", action="store_true")
    ap.add_argument("--skip-cert-trust", action="store_true")
    ap.add_argument("--hide-vcruntime", action="store_true", help="hide System32's VC++ runtime for one check (administrator; CI only)")
    sys.exit(Smoke(ap.parse_args()).main())


if __name__ == "__main__":
    main()
