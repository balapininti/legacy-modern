#!/usr/bin/env python3
"""Runs the built toolkit over the owner's validation repos and records what happened, failures included."""
import json, os, pathlib, subprocess, sys, time
root = pathlib.Path(__file__).resolve().parent.parent
REPOS = {
    "carddemo": "https://github.com/aws-samples/aws-mainframe-modernization-carddemo",
    "bankdemo": "https://github.com/RocketSoftwareCOBOLandMainframe/BankDemo",
    "bank-of-z": "https://github.com/IBM/Bank-of-Z",
    # Archived: main holds only a README, and the code is on this branch.
    "cbsa": "https://github.com/cicsdev/cics-banking-sample-application-cbsa#July2024Refresh",
    "genapp": "https://github.com/cicsdev/cics-genapp",
    "dsf": "https://github.com/navikt/DSF",
    "taxe-fonciere": "https://github.com/etalab/taxe-fonciere",
    "bricks": "https://github.com/moshix/BRICKS_TS",
    "cobol-unit-test": "https://github.com/neopragma/cobol-unit-test",
    "cobol-course": "https://github.com/openmainframeproject/cobol-programming-course",
}
names = sys.argv[1:] or list(REPOS)
jar = os.environ.get("TOOLKIT_JAR", str(root / "cli/target/toolkit.jar"))
out = root / ".cache/validation"; out.mkdir(parents=True, exist_ok=True)
results = {}
def sh(*a, **k): return subprocess.run(a, capture_output=True, text=True, **k)
for n in names:
    d = root / ".cache/corpora" / n
    if not d.exists():
        url, _, branch = REPOS[n].partition("#")
        r = sh("git", "clone", "-q", "--depth", "1", *(["--branch", branch] if branch else []), url, str(d))
        if r.returncode: results[n] = {"error": r.stderr[-300:]}; print(n, "CLONE FAILED"); continue
    sha = sh("git", "-C", str(d), "rev-parse", "HEAD").stdout.strip()
    db = "v_" + n.replace("-", "_")
    sh("docker", "exec", "toolkit-perf-pg", "psql", "-U", "toolkit", "-d", "toolkit", "-qc", f"DROP DATABASE IF EXISTS {db}")
    sh("docker", "exec", "toolkit-perf-pg", "psql", "-U", "toolkit", "-d", "toolkit", "-qc", f"CREATE DATABASE {db}")
    env = dict(os.environ, TOOLKIT_DB_URL=f"jdbc:postgresql://localhost:55433/{db}", TOOLKIT_DB_USER="toolkit", TOOLKIT_DB_PASSWORD="toolkit")
    rep = out / f"{n}.json"; t0 = time.time()
    r = subprocess.run(["java", "-Xmx8g", "-jar", jar, "discover", "--root", str(d), "--threads", "8", "--member-timeout-seconds", "120", "--report", str(rep)], capture_output=True, text=True, env=env, cwd=out)
    el = round(time.time() - t0, 1)
    if r.returncode or not rep.exists():
        results[n] = {"sha": sha, "error": r.stderr[-800:]}; print(n, "DISCOVER FAILED", r.stderr[-300:]); continue
    i = subprocess.run(["java", "-jar", jar, "index"], capture_output=True, text=True, env=env, cwd=out)
    j = json.load(open(rep)); st = j["parse_status"]; ty = j["members_by_type"]
    fails = {}
    for f in j["parse_failures"]:
        k = f["type"]; fails[k] = fails.get(k, 0) + 1
    results[n] = dict(in_context=st.get("in_context", 0), koopa=j["dialect_features"].get("FALLBACK PARSER koopa", 0), sha=sha, seconds=el, lines=j["lines"], types=ty, status=st, programs=j["programs"], jobs=j["jobs"], maps=j["maps"], failures_by_type=fails, static_calls=j["static_calls"], missing_copybooks=j["missing_copybooks"], unresolved=j["unresolved_dynamic_calls"], dead=j["dead_code_candidates"], edges=sum(j["edges_by_type"].values()), features=j["dialect_features"], index=i.stdout.strip()[:80])
    print(n, "lines", j["lines"], "cobol", ty.get("cobol", 0), "cpy", ty.get("copybook", 0), "jcl", ty.get("jcl", 0), "failed", st.get("failed", 0), fails, "static%", j["static_calls"]["resolved_pct"], "missingcpy", j["missing_copybooks"], f"{el}s")
    sys.stdout.flush()
json.dump(results, open(out / "summary.json", "w"), indent=1)
