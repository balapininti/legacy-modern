#!/usr/bin/env python3
"""Fails when a distributable dependency is not Apache, MIT or BSD and not a named exception. See ADR 0003."""
import pathlib, re, subprocess, sys
root = pathlib.Path(__file__).resolve().parent.parent
out = root / ".cache" / "lic"
subprocess.run(["mvn", "-q", "-B", "-DskipTests", "org.codehaus.mojo:license-maven-plugin:2.5.0:aggregate-add-third-party",
                "-Dlicense.excludedScopes=test,provided", f"-Dlicense.outputDirectory={out}", "-Dlicense.thirdPartyFilename=third-party.txt"],
               cwd=root, check=True)
OK = re.compile(r"apache|\bmit\b|\bbsd\b|mit-0|postgresql|public domain|cc0|edl 1\.0|eclipse distribution", re.I)
exc = {l.split()[0] for l in (root / "scripts/license-exceptions.txt").read_text().splitlines() if l.strip() and not l.startswith("#")}
bad, n = [], 0
for line in (out / "third-party.txt").read_text().splitlines():
    m = re.match(r"\s*((?:\([^)]*\)\s*)+)(.*)\((\S+?):(\S+?):(\S+)", line)
    if not m:
        continue
    n += 1
    if f"{m.group(3)}:{m.group(4)}" not in exc and not OK.search(m.group(1)):
        bad.append(line.strip())
print(f"{n} dependencies checked, {len(bad)} violations")
print("\n".join(bad))
sys.exit(1 if bad or n == 0 else 0)
