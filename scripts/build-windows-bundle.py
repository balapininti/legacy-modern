#!/usr/bin/env python3
"""Builds the Windows client bundle: target/toolkit-<version>-windows-x64.zip and its .sha256.

    python3 scripts/build-windows-bundle.py --jar cli/target/toolkit.jar --ui ui/dist/ui/browser \
        --pgvector <dir with lib/vector.dll and share/extension/vector*> --out target/

`--pgvector none` makes a lexical-only bundle, named toolkit-<version>-windows-x64-lexical.zip.

Python 3.9+, standard library only; runs on macOS, Linux and Windows. Every third-party download is pinned by URL
and SHA-256 and cached in .cache/downloads. Nothing in the bundle is fetched at install or run time.

Also needs: npm (installs tools/ng-extract's production dependencies into the bundle, so the client needs no
network), and an SBOM of the jar (--sbom; by default `mvnw cyclonedx:makeAggregateBom` is run to make target/bom.json).
"""
import argparse
import datetime
import fnmatch
import hashlib
import json
import os
import pathlib
import shutil
import stat
import struct
import subprocess
import sys
import tempfile
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent

# ---- pinned downloads -------------------------------------------------------------------------------------------
# PostgreSQL: the EDB "zip archive" binaries for Windows x64, the same file .github/workflows/windows.yml builds
#   pgvector against (https://www.enterprisedb.com/download-postgresql-binaries).
# Temurin: api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jre&os=windows&vendor=eclipse
# Node: the 24.x LTS (Krypton) win-x64 zip; checksum from https://nodejs.org/dist/v24.22.3/SHASUMS256.txt
# WinSW: the v2.12.0 GitHub release; the checksum was computed from the release asset (the release publishes none).
PINS = {
    "postgresql": {
        "url": "https://get.enterprisedb.com/postgresql/postgresql-17.12-1-windows-x64-binaries.zip",
        "file": "postgresql-17.12-1-windows-x64-binaries.zip",
        "sha256": "a2cd8a774389347b70be7748ac4a37ef43cb6ee539c5fc3e85ae264b15e4a04c",
        "version": "17.12-1",
    },
    "jre": {
        "url": "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jre_x64_windows_hotspot_21.0.13_9.zip",
        "file": "OpenJDK21U-jre_x64_windows_hotspot_21.0.13_9.zip",
        "sha256": "2332c0b4cff6bed722da3567c91d5e47568bcba863fa4945d0a8d6fc76158628",
        "version": "21.0.13+9",
    },
    "node": {
        "url": "https://nodejs.org/dist/v24.22.3/node-v24.22.3-win-x64.zip",
        "file": "node-v24.22.3-win-x64.zip",
        "sha256": "e612af99f626ab415ea225af6bce5c971feb448cb5cae250bb1c3fa50c5f7d9f",
        "version": "24.22.3",
    },
    "winsw": {
        "url": "https://github.com/winsw/winsw/releases/download/v2.12.0/WinSW-x64.exe",
        "file": "WinSW-x64-v2.12.0.exe",
        "sha256": "05b82d46ad331cc16bdc00de5c6332c1ef818df8ceefcd49c726553209b3a0da",
        "version": "2.12.0",
    },
    "winsw-license": {
        "url": "https://raw.githubusercontent.com/winsw/winsw/v2.12.0/LICENSE.txt",
        "file": "WinSW-v2.12.0-LICENSE.txt",
        "sha256": "1cdf703c10a70e5973bf3acf2a5eeabe7746237155b92db2034aeae26fdf7802",
        "version": "2.12.0",
    },
}

# ---- what we keep of PostgreSQL ---------------------------------------------------------------------------------
# Programs the launcher, setup, backups and upgrades use. Their DLLs are found by reading the PE import tables.
PG_PROGRAMS = ["postgres", "initdb", "pg_ctl", "psql", "pg_dump", "pg_dumpall", "pg_restore", "pg_isready",
               "pg_controldata", "pg_resetwal", "pg_upgrade", "vacuumdb", "reindexdb"]
# Server modules: PL/pgSQL (initdb creates it), pg_trgm (lexical search), Snowball stemmers (initdb's text search
# configurations), the encoding conversions (loaded on demand by the server), and vector from --pgvector.
PG_LIB_KEEP = ["plpgsql.dll", "pg_trgm.dll", "dict_snowball.dll", "*_and_*.dll"]
PG_EXTENSIONS_KEEP = ["plpgsql*", "pg_trgm*", "vector*"]
# Provided by Windows itself (or, for the VC++ runtime, by runtime/jre/bin; see the VC++ note in LICENSES).
SYSTEM_DLLS = {"kernel32.dll", "advapi32.dll", "ws2_32.dll", "secur32.dll", "wldap32.dll", "shell32.dll",
               "crypt32.dll", "user32.dll", "bcrypt.dll", "ole32.dll", "oleaut32.dll", "shlwapi.dll", "userenv.dll",
               "netapi32.dll", "iphlpapi.dll", "dnsapi.dll", "normaliz.dll", "rpcrt4.dll", "gdi32.dll", "version.dll"}
VC_RUNTIME = {"vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll"}

# License of every DLL kept from the EDB zip's bin folder. The texts are in LICENSES/postgresql/.
PG_DLL_LICENSES = {
    "libpq.dll": ("PostgreSQL License", "PostgreSQL 17.12, EDB Windows build"),
    "libintl-9.dll": ("LGPL-2.1-or-later (GNU gettext 0.19.8 libintl; EDB's notice ships the LGPL-3.0 text)", "https://ftp.gnu.org/gnu/gettext/gettext-0.19.8.tar.xz"),
    "libiconv-2.dll": ("LGPL-2.1-or-later (GNU libiconv 1.15; EDB's notice ships the LGPL-3.0 text)", "https://ftp.gnu.org/gnu/libiconv/libiconv-1.15.tar.gz"),
    "libcrypto-3-x64.dll": ("Apache-2.0 (OpenSSL 3.5)", "https://www.openssl.org/source/"),
    "libssl-3-x64.dll": ("Apache-2.0 (OpenSSL 3.5)", "https://www.openssl.org/source/"),
    "icudt67.dll": ("Unicode/ICU License", "https://github.com/unicode-org/icu/releases/tag/release-67-1"),
    "icuin67.dll": ("Unicode/ICU License", "https://github.com/unicode-org/icu/releases/tag/release-67-1"),
    "icuuc67.dll": ("Unicode/ICU License", "https://github.com/unicode-org/icu/releases/tag/release-67-1"),
    "icuio67.dll": ("Unicode/ICU License", "https://github.com/unicode-org/icu/releases/tag/release-67-1"),
    "icutu67.dll": ("Unicode/ICU License", "https://github.com/unicode-org/icu/releases/tag/release-67-1"),
    "libxml2.dll": ("MIT (libxml2 2.15)", "https://gitlab.gnome.org/GNOME/libxml2"),
    "libxslt.dll": ("MIT (libxslt)", "https://gitlab.gnome.org/GNOME/libxslt"),
    "liblz4.dll": ("BSD-2-Clause (LZ4 library)", "https://github.com/lz4/lz4"),
    "libzstd.dll": ("BSD-3-Clause (zstd, dual BSD/GPLv2; used under BSD)", "https://github.com/facebook/zstd"),
    "zlib1.dll": ("Zlib", "https://zlib.net/"),
    "libcurl.dll": ("curl License (MIT-style)", "https://curl.se/"),
}


def die(msg):
    print("build-windows-bundle: " + msg, file=sys.stderr)
    sys.exit(1)


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def fetch(name, cache):
    pin = PINS[name]
    dest = cache / pin["file"]
    if dest.exists() and sha256(dest) == pin["sha256"]:
        return dest
    cache.mkdir(parents=True, exist_ok=True)
    print(f"downloading {pin['url']}")
    tmp = dest.with_suffix(dest.suffix + ".part")
    req = urllib.request.Request(pin["url"], headers={"User-Agent": "toolkit-bundle-builder"})
    with urllib.request.urlopen(req, timeout=600) as r, open(tmp, "wb") as f:
        shutil.copyfileobj(r, f, 1 << 20)
    got = sha256(tmp)
    if got != pin["sha256"]:
        tmp.unlink()
        die(f"{pin['url']}: SHA-256 {got} does not match the pinned {pin['sha256']}")
    tmp.replace(dest)
    return dest


# ---- PE import tables (which DLLs an .exe or .dll loads) ----------------------------------------------------------
def pe_imports(data):
    """Names of the DLLs in the import and delay-import tables of a PE32+ or PE32 image."""
    pe = struct.unpack_from("<I", data, 0x3C)[0]
    if data[pe:pe + 4] != b"PE\0\0":
        raise ValueError("not a PE image")
    nsec = struct.unpack_from("<H", data, pe + 6)[0]
    optsz = struct.unpack_from("<H", data, pe + 20)[0]
    opt = pe + 24
    magic = struct.unpack_from("<H", data, opt)[0]
    dirs = opt + (112 if magic == 0x20B else 96)
    sections = []
    for i in range(nsec):
        s = opt + optsz + i * 40
        vsize, va, rawsize, raw = struct.unpack_from("<IIII", data, s + 8)
        sections.append((va, max(vsize, rawsize), raw))

    def off(rva):
        for va, size, raw in sections:
            if va <= rva < va + size:
                return rva - va + raw
        raise ValueError(f"rva {rva:#x} outside every section")

    names = []
    for index, size, name_at in ((1, 20, 12), (13, 32, 4)):
        rva = struct.unpack_from("<I", data, dirs + index * 8)[0]
        if not rva:
            continue
        o = off(rva)
        while True:
            name_rva = struct.unpack_from("<I", data, o + name_at)[0]
            if not name_rva:
                break
            n = off(name_rva)
            names.append(data[n:data.index(b"\0", n)].decode("ascii"))
            o += size
    return names


def pe_version(data):
    i = data.find(b"\xbd\x04\xef\xfe")
    if i < 0:
        return ""
    ms, ls = struct.unpack_from("<II", data, i + 8)
    return f"{ms >> 16}.{ms & 0xFFFF}.{ls >> 16}.{ls & 0xFFFF}"


# ---- staging ----------------------------------------------------------------------------------------------------
def pom_version():
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    v = ET.parse(ROOT / "pom.xml").getroot().find("m:version", ns)
    if v is None or not v.text:
        die("no <version> in pom.xml")
    return v.text.strip()


def extract_postgres(zpath, dest, pgvector):
    """Unzips the parts of pgsql/ we ship into dest (runtime/pgsql). Returns {dll name: version} of bin DLLs kept."""
    z = zipfile.ZipFile(zpath)
    names = {i.filename: i for i in z.infolist()}
    bin_files = {n[len("pgsql/bin/"):].lower(): n for n in names if n.startswith("pgsql/bin/") and n.count("/") == 2 and not n.endswith("/")}
    lib_files = {n[len("pgsql/lib/"):]: n for n in names if n.startswith("pgsql/lib/") and n.count("/") == 2 and not n.endswith("/")}

    keep = []
    for p in PG_PROGRAMS:
        n = f"pgsql/bin/{p}.exe"
        if n not in names:
            die(f"{zpath.name} has no {n}")
        keep.append(n)
    libs = [n for f, n in lib_files.items() if any(fnmatch.fnmatch(f.lower(), pat) for pat in PG_LIB_KEEP)]
    keep += libs

    # The transitive closure of DLL imports, resolved against pgsql/bin.
    images = {n: z.read(n) for n in keep}
    if pgvector:
        images["vector.dll"] = (pgvector / "lib" / "vector.dll").read_bytes()
    need, seen, missing, vc = list(images.values()), set(), set(), set()
    bin_dlls = {}
    while need:
        for dll in pe_imports(need.pop()):
            d = dll.lower()
            if d in seen:
                continue
            seen.add(d)
            if d == "postgres.exe":  # server modules link against the server itself, which is shipped anyway
                continue
            if d in bin_files:
                data = z.read(bin_files[d])
                bin_dlls[bin_files[d]] = data
                need.append(data)
            elif d in VC_RUNTIME:
                vc.add(d)
            elif d.startswith("api-ms-win-") or d.startswith("ext-ms-") or d in SYSTEM_DLLS:
                continue
            else:
                missing.add(dll)
    if missing:
        die("DLLs imported by the shipped PostgreSQL programs but found neither in pgsql/bin nor in Windows: " + ", ".join(sorted(missing)))
    keep += list(bin_dlls)
    unknown = [n.rsplit("/", 1)[1] for n in bin_dlls if n.rsplit("/", 1)[1].lower() not in PG_DLL_LICENSES]
    if unknown:
        die("no license recorded for " + ", ".join(unknown) + ": add it to PG_DLL_LICENSES")

    for n, i in names.items():
        if n.startswith("pgsql/share/") and not n.endswith("/"):
            rel = n[len("pgsql/share/"):]
            if rel.startswith(("locale/", "doc/")):  # message translations (the toolkit runs PostgreSQL in English) and docs
                continue
            if rel.startswith("extension/") and not any(fnmatch.fnmatch(rel[len("extension/"):], p) for p in PG_EXTENSIONS_KEEP):
                continue
            keep.append(n)
    for n in keep:
        out = dest / n[len("pgsql/"):]
        out.parent.mkdir(parents=True, exist_ok=True)
        with z.open(n) as src, open(out, "wb") as f:
            shutil.copyfileobj(src, f)
    if pgvector:
        shutil.copy2(pgvector / "lib" / "vector.dll", dest / "lib" / "vector.dll")
        ext = list((pgvector / "share" / "extension").glob("vector*"))
        if not any(p.name == "vector.control" for p in ext):
            die(f"{pgvector}/share/extension has no vector.control")
        for p in ext:
            shutil.copy2(p, dest / "share" / "extension" / p.name)
    return {n.rsplit("/", 1)[1]: pe_version(d) for n, d in bin_dlls.items()}, sorted(vc), z


def extract_prefix(zpath, prefix, dest, only=None):
    """Unzips every entry under prefix/ into dest, or only the entries whose path below prefix is in `only`."""
    with zipfile.ZipFile(zpath) as z:
        for i in z.infolist():
            if not i.filename.startswith(prefix + "/") or i.filename.endswith("/"):
                continue
            rel = i.filename[len(prefix) + 1:]
            if only is not None and rel not in only:
                continue
            out = dest / rel
            out.parent.mkdir(parents=True, exist_ok=True)
            with z.open(i) as src, open(out, "wb") as f:
                shutil.copyfileobj(src, f)


def npm():
    exe = shutil.which("npm.cmd" if os.name == "nt" else "npm") or shutil.which("npm")
    if not exe:
        die("npm is needed to install tools/ng-extract's dependencies into the bundle")
    return exe


def stage_ng_extract(dest):
    src = ROOT / "tools" / "ng-extract"
    dest.mkdir(parents=True)
    for f in ("extract.js", "package.json", "package-lock.json"):
        shutil.copy2(src / f, dest / f)
    # Production dependencies only, no install scripts; the result is plain JavaScript that runs offline anywhere.
    subprocess.run([npm(), "ci", "--omit=dev", "--ignore-scripts", "--no-audit", "--no-fund", "--loglevel=error"], cwd=dest, check=True)
    lic = []
    for pkg in sorted((dest / "node_modules").glob("**/package.json")):
        if pkg.parent.parent.name != "node_modules" and pkg.parent.parent.parent.name != "node_modules":
            continue
        try:
            meta = json.loads(pkg.read_text(encoding="utf-8"))
        except (ValueError, OSError):
            continue
        if "name" not in meta or "version" not in meta:
            continue
        lic.append((meta["name"], meta["version"], meta.get("license", "UNKNOWN"), str(pkg.parent.relative_to(dest))))
    return lic


def sbom_components(sbom):
    out = []
    for c in sbom.get("components", []):
        ls = []
        for l in c.get("licenses", []):
            if "expression" in l:
                ls.append(l["expression"])
            else:
                lic = l.get("license", {})
                ls.append(lic.get("id") or lic.get("name") or "?")
        out.append({"group": c.get("group", ""), "name": c["name"], "version": c.get("version", ""),
                    "licenses": ls or ["(none in POM; see scripts/license-exceptions.txt)"], "purl": c.get("purl", "")})
    return out


def write_licenses(stage, lic_dir, version, pg_zip, pg_dlls, vc, pgvector, jar, sbom, ng_licenses, winsw_license):
    lic_dir.mkdir(parents=True)
    (lic_dir / "postgresql").mkdir()
    for n in ("server_license.txt", "commandlinetools_3rd_party_licenses.txt"):
        (lic_dir / "postgresql" / n).write_bytes(pg_zip.read("pgsql/" + n))
    if pgvector:
        (lic_dir / "pgvector").mkdir()
        src = pgvector / "LICENSE.pgvector"
        if not src.exists():
            die(f"{pgvector} has no LICENSE.pgvector")
        shutil.copy2(src, lic_dir / "pgvector" / "LICENSE")
    (lic_dir / "node").mkdir()
    shutil.copy2(stage / "runtime" / "node" / "LICENSE", lic_dir / "node" / "LICENSE")
    (lic_dir / "winsw").mkdir()
    shutil.copy2(winsw_license, lic_dir / "winsw" / "LICENSE.txt")
    (lic_dir / "temurin").mkdir()
    shutil.copy2(stage / "runtime" / "jre" / "NOTICE", lic_dir / "temurin" / "NOTICE")
    for n in ("LICENSE", "ADDITIONAL_LICENSE_INFO", "ASSEMBLY_EXCEPTION"):
        p = stage / "runtime" / "jre" / "legal" / "java.base" / n
        if p.exists():
            shutil.copy2(p, lic_dir / "temurin" / n)
    (lic_dir / "ng-extract").mkdir()
    for p in sorted((stage / "tools" / "ng-extract" / "node_modules").glob("*/LICENSE*")) + \
            sorted((stage / "tools" / "ng-extract" / "node_modules").glob("*/ThirdPartyNoticeText.txt")):
        shutil.copy2(p, lic_dir / "ng-extract" / f"{p.parent.name}-{p.name}")

    jar_libs = []
    with zipfile.ZipFile(jar) as j:
        jar_libs = sorted(n[len("BOOT-INF/lib/"):] for n in j.namelist() if n.startswith("BOOT-INF/lib/") and n.endswith(".jar"))
    comps = sbom_components(sbom)
    by_file = {f"{c['name']}-{c['version']}.jar": c for c in comps}

    rows = []

    def row(path, component, license_, source):
        rows.append((path, component, license_, source))

    for p in sorted(stage.rglob("*")):
        if not p.is_file():
            continue
        rel = p.relative_to(stage).as_posix()
        low = p.name.lower()
        if not (low.endswith(".exe") or low.endswith(".dll") or low.endswith(".jar")):
            continue
        if rel.startswith("runtime/pgsql/"):
            if rel == "runtime/pgsql/lib/vector.dll":
                row(rel, "pgvector 0.8.7, built by this project's CI from source against PostgreSQL 17.12", "PostgreSQL License", "https://github.com/pgvector/pgvector/tree/v0.8.7")
            elif rel.startswith("runtime/pgsql/bin/") and p.name in PG_DLL_LICENSES:
                lic, src = PG_DLL_LICENSES[p.name]
                row(rel, f"{p.name} {pg_dlls.get(p.name, '')}".strip() + ", from the EDB PostgreSQL 17.12-1 zip", lic, src)
            else:
                row(rel, "PostgreSQL 17.12 (EDB Windows x64 binaries 17.12-1)", "PostgreSQL License", PINS["postgresql"]["url"])
        elif rel.startswith("runtime/jre/"):
            if low in VC_RUNTIME or low == "ucrtbase.dll" or low.startswith("api-ms-win-"):
                row(rel, f"Microsoft Visual C++ / Universal C runtime, shipped inside Temurin {PINS['jre']['version']}",
                    "Microsoft Visual Studio Distributable Code, redistributed by Eclipse Adoptium (see runtime/jre/legal)", PINS["jre"]["url"])
            else:
                row(rel, f"Eclipse Temurin JRE {PINS['jre']['version']}", "GPL-2.0-only WITH Classpath-exception-2.0 (see runtime/jre/legal)", PINS["jre"]["url"])
        elif rel.startswith("runtime/node/"):
            row(rel, f"Node.js {PINS['node']['version']}", "MIT, with bundled third-party licenses (LICENSES/node/LICENSE)", PINS["node"]["url"])
        elif rel.startswith("service/"):
            row(rel, f"WinSW {PINS['winsw']['version']} (WinSW-x64.exe, self-contained .NET Core 3.1, renamed)",
                "MIT (WinSW; the bundled .NET runtime is MIT too)", PINS["winsw"]["url"])
        elif rel == "lib/toolkit.jar":
            row(rel, f"Mainframe Modernization Toolkit {version}", "this project", "this repository")
            for lib in jar_libs:
                c = by_file.get(lib)
                if c is None:  # a classifier jar (netty's native ones): name-version-classifier.jar
                    hits = [x for x in comps if lib.startswith(f"{x['name']}-{x['version']}-")]
                    c = max(hits, key=lambda x: len(x["name"])) if hits else None
                if c is None and lib.startswith("spring-boot-jarmode-tools-"):
                    # Added to every executable jar by spring-boot-maven-plugin, so it is in no dependency tree.
                    c = {"group": "org.springframework.boot", "name": "spring-boot-jarmode-tools", "version": lib[len("spring-boot-jarmode-tools-"):-4],
                         "licenses": ["Apache-2.0"], "purl": "pkg:maven/org.springframework.boot/spring-boot-jarmode-tools"}
                if c is None and lib.startswith("toolkit-"):
                    row(f"{rel}!/BOOT-INF/lib/{lib}", "module of this project", "this project", "this repository")
                elif c is None:
                    row(f"{rel}!/BOOT-INF/lib/{lib}", lib, "NOT IN SBOM", "")
                else:
                    row(f"{rel}!/BOOT-INF/lib/{lib}", f"{c['group']}:{c['name']}:{c['version']}", " OR ".join(c["licenses"]), c["purl"])
        else:
            row(rel, "?", "UNKNOWN", "")
    for name, ver, lic, path in ng_licenses:
        row(f"tools/ng-extract/{path}", f"npm {name}@{ver}", lic, f"https://www.npmjs.com/package/{name}/v/{ver}")

    unknown = [r for r in rows if r[2] in ("UNKNOWN", "NOT IN SBOM")]
    if unknown:
        die("license unknown for: " + ", ".join(r[0] for r in unknown))

    lines = [
        f"# Third-party software in toolkit {version} for Windows x64",
        "",
        "Every program, library and archive in this bundle, with its license and where it comes from. Generated by",
        "scripts/build-windows-bundle.py from the pinned downloads and the toolkit's SBOM (sbom.json, CycloneDX).",
        "",
        "## Not permissively licensed (accepted exceptions, ADR 0008)",
        "",
        "- runtime/jre (Eclipse Temurin): GPL-2.0 with the Classpath Exception. Unmodified. Source: https://github.com/adoptium/jdk21u",
        "- runtime/pgsql/bin/libintl-9.dll (GNU gettext 0.19.8) and libiconv-2.dll (GNU libiconv 1.15): LGPL. Unmodified",
        "  copies from the EDB PostgreSQL zip, loaded dynamically by postgres.exe, psql.exe and the other programs; you may",
        "  replace them with interface-compatible builds. Their source: https://ftp.gnu.org/gnu/gettext/gettext-0.19.8.tar.xz,",
        "  https://ftp.gnu.org/gnu/libiconv/libiconv-1.15.tar.gz; on request we provide the exact source for three years.",
        "  License texts: LICENSES/postgresql/commandlinetools_3rd_party_licenses.txt.",
        "",
        "## Microsoft Visual C++ runtime",
        "",
        f"The PostgreSQL programs import VCRUNTIME140.dll (and ICU, MSVCP140.dll): {', '.join(vc) or 'none'}. The EDB zip does",
        "not include them. The bundle does not add any: the launcher puts runtime\\jre\\bin, where Temurin ships these",
        "DLLs, on the PostgreSQL processes' PATH, so a machine without the Visual C++ Redistributable still works. A",
        "system-wide copy (C:\\Windows\\System32) is used first when present. The Universal C runtime (api-ms-win-crt-*)",
        "is part of Windows 10 and later.",
        "",
        "## Inventory",
        "",
        "| File | Component | License | Source |",
        "|---|---|---|---|",
    ]
    for r in rows:
        lines.append("| " + " | ".join(x.replace("|", "\\|") for x in r) + " |")
    lines += ["", "## License texts", "",
              "- PostgreSQL and EDB's third-party notices (OpenSSL, ICU, gettext, libiconv, libxml2, LZ4, zstd, zlib, curl): LICENSES/postgresql/",
              "- pgvector: LICENSES/pgvector/LICENSE" if pgvector else "- pgvector: not in this lexical-only bundle",
              "- Temurin: LICENSES/temurin/ and runtime/jre/legal/ (one folder per JDK module, with its third-party notices)",
              "- Node.js: LICENSES/node/LICENSE", "- WinSW: LICENSES/winsw/LICENSE.txt",
              "- ng-extract's npm packages: LICENSES/ng-extract/",
              "- The toolkit jar's dependencies: their licenses are named above and in sbom.json; texts in each jar's META-INF/", ""]
    (lic_dir / "INVENTORY.md").write_text("\n".join(lines), encoding="utf-8", newline="\r\n")
    return rows


def write_sbom(path, sbom, version, pgvector, ng_licenses):
    extra = [
        ("PostgreSQL", PINS["postgresql"]["version"], "PostgreSQL", PINS["postgresql"]["url"], PINS["postgresql"]["sha256"]),
        ("Eclipse Temurin JRE", PINS["jre"]["version"], "GPL-2.0-only WITH Classpath-exception-2.0", PINS["jre"]["url"], PINS["jre"]["sha256"]),
        ("Node.js", PINS["node"]["version"], "MIT", PINS["node"]["url"], PINS["node"]["sha256"]),
        ("WinSW", PINS["winsw"]["version"], "MIT", PINS["winsw"]["url"], PINS["winsw"]["sha256"]),
    ]
    if pgvector:
        extra.append(("pgvector", "0.8.7", "PostgreSQL", "https://github.com/pgvector/pgvector/tree/v0.8.7", None))
    comps = list(sbom.get("components", []))
    for name, ver, lic, url, digest in extra:
        c = {"type": "application", "name": name, "version": ver, "licenses": [{"expression": lic}],
             "externalReferences": [{"type": "distribution", "url": url}], "bom-ref": f"bundle:{name}@{ver}"}
        if digest:
            c["hashes"] = [{"alg": "SHA-256", "content": digest}]
        comps.append(c)
    for name, ver, lic, _ in ng_licenses:
        comps.append({"type": "library", "name": name, "version": ver, "purl": f"pkg:npm/{name}@{ver}",
                      "licenses": [{"expression": lic}], "bom-ref": f"pkg:npm/{name}@{ver}"})
    out = dict(sbom)
    out["components"] = comps
    meta = dict(out.get("metadata", {}))
    meta["timestamp"] = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    meta["component"] = {"type": "application", "name": "toolkit-windows-x64", "version": version, "bom-ref": "toolkit-windows-x64"}
    out["metadata"] = meta
    path.write_text(json.dumps(out, indent=1), encoding="utf-8")


def make_sbom(arg):
    if arg:
        return json.loads(pathlib.Path(arg).read_text(encoding="utf-8"))
    mvnw = ROOT / ("mvnw.cmd" if os.name == "nt" else "mvnw")
    subprocess.run([str(mvnw), "-B", "-q", "org.cyclonedx:cyclonedx-maven-plugin:2.9.3:makeAggregateBom"], cwd=ROOT, check=True)
    return json.loads((ROOT / "target" / "bom.json").read_text(encoding="utf-8"))


def zip_tree(src_root, top, zpath):
    tmp = zpath.with_suffix(".zip.part")
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for p in sorted(src_root.rglob("*")):
            arc = top + "/" + p.relative_to(src_root).as_posix()
            if p.is_dir():
                continue
            zi = zipfile.ZipInfo.from_file(p, arc)
            zi.compress_type = zipfile.ZIP_DEFLATED
            zi.external_attr = (stat.S_IFREG | 0o644) << 16
            with open(p, "rb") as f:
                z.writestr(zi, f.read(), compresslevel=9)
    tmp.replace(zpath)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--jar", required=True, help="the toolkit jar (cli/target/toolkit.jar)")
    ap.add_argument("--ui", required=True, help="the Angular production build (ui/dist/ui/browser)")
    ap.add_argument("--pgvector", required=True, help="folder with lib/vector.dll, share/extension/vector* and LICENSE.pgvector, or 'none'")
    ap.add_argument("--out", default="target", help="output folder (default: target)")
    ap.add_argument("--sbom", help="CycloneDX JSON of the jar (default: run mvnw cyclonedx:makeAggregateBom)")
    ap.add_argument("--cache", default=str(ROOT / ".cache" / "downloads"), help="download cache (default: .cache/downloads)")
    ap.add_argument("--keep-staging", action="store_true", help="leave the unzipped bundle next to the zip")
    a = ap.parse_args()

    jar, ui, out, cache = pathlib.Path(a.jar), pathlib.Path(a.ui), pathlib.Path(a.out), pathlib.Path(a.cache)
    pgvector = None if a.pgvector == "none" else pathlib.Path(a.pgvector)
    if not jar.is_file():
        die(f"no jar at {jar}")
    if not (ui / "index.html").is_file():
        die(f"no index.html in {ui}")
    if pgvector and not (pgvector / "lib" / "vector.dll").is_file():
        die(f"no lib/vector.dll in {pgvector}")

    version = pom_version()
    name = f"toolkit-{version}-windows-x64" + ("" if pgvector else "-lexical")
    files = {k: fetch(k, cache) for k in PINS}
    sbom = make_sbom(a.sbom)

    out.mkdir(parents=True, exist_ok=True)
    work = pathlib.Path(tempfile.mkdtemp(prefix="bundle-", dir=out))
    stage = work / "toolkit"
    try:
        stage.mkdir()
        win = ROOT / "deploy" / "windows"
        for f in ("toolkit.cmd", "start.cmd", "stop.cmd"):
            data = (win / f).read_bytes()
            if b"\r\n" not in data or data.replace(b"\r\n", b"").find(b"\n") >= 0:
                die(f"{win / f} must have CRLF line ends (see .gitattributes)")
            (stage / f).write_bytes(data)
        install = (ROOT / "deploy" / "INSTALL.md").read_text(encoding="utf-8")
        (stage / "INSTALL.md").write_text(install, encoding="utf-8", newline="\r\n")
        # The guide for after the install, and the Copilot CLI skill it tells users to copy.
        using = (ROOT / "deploy" / "USING.md").read_text(encoding="utf-8")
        (stage / "USING.md").write_text(using, encoding="utf-8", newline="\r\n")
        skills = ROOT / "deploy" / "skills"
        for src in sorted(p for p in skills.rglob("*") if p.is_file()):
            dst = stage / "skills" / src.relative_to(skills)
            dst.parent.mkdir(parents=True, exist_ok=True)
            # LF kept: a skill's YAML front matter is read by agents on every platform
            dst.write_text(src.read_text(encoding="utf-8"), encoding="utf-8", newline="\n")
        (stage / "lib").mkdir()
        shutil.copy2(jar, stage / "lib" / "toolkit.jar")
        shutil.copytree(ui, stage / "ui")
        (stage / "config").mkdir()
        example = (win / "config" / "toolkit.yaml.example").read_text(encoding="utf-8")
        (stage / "config" / "toolkit.yaml.example").write_text(example, encoding="utf-8", newline="\r\n")

        print("PostgreSQL")
        pg_dlls, vc, pg_zip = extract_postgres(files["postgresql"], stage / "runtime" / "pgsql", pgvector)
        print("Temurin JRE")
        extract_prefix(files["jre"], "jdk-21.0.13+9-jre", stage / "runtime" / "jre")
        if not (stage / "runtime" / "jre" / "bin" / "java.exe").is_file():
            die("the JRE zip has no bin/java.exe")
        missing_vc = [d for d in vc if not (stage / "runtime" / "jre" / "bin" / d).is_file()]
        if missing_vc:
            die("the PostgreSQL programs need " + ", ".join(missing_vc) + ", which Temurin no longer ships in bin/")
        print("Node")
        extract_prefix(files["node"], "node-v24.22.3-win-x64", stage / "runtime" / "node", only={"node.exe", "LICENSE"})
        print("ng-extract")
        ng = stage_ng_extract(stage / "tools" / "ng-extract")
        print("WinSW")
        (stage / "service").mkdir()
        shutil.copy2(files["winsw"], stage / "service" / "toolkit.exe")
        for f in ("toolkit.xml", "service.cmd"):
            data = (win / "service" / f).read_bytes()
            if f.endswith(".cmd") and (b"\r\n" not in data or data.replace(b"\r\n", b"").find(b"\n") >= 0):
                die(f"{win / 'service' / f} must have CRLF line ends")
            (stage / "service" / f).write_bytes(data)
        print("LICENSES and sbom.json")
        rows = write_licenses(stage, stage / "LICENSES", version, pg_zip, pg_dlls, vc, pgvector, jar, sbom, ng, files["winsw-license"])
        write_sbom(stage / "sbom.json", sbom, version, pgvector, ng)

        zpath = out / f"{name}.zip"
        print(f"zipping {zpath}")
        zip_tree(stage, "toolkit", zpath)
        digest = sha256(zpath)
        (out / f"{name}.zip.sha256").write_text(f"{digest}  {name}.zip\n", encoding="ascii")
        size = zpath.stat().st_size
        unpacked = sum(p.stat().st_size for p in stage.rglob("*") if p.is_file())
        print(f"{zpath}: {size / 1e6:.1f} MB ({unpacked / 1e6:.1f} MB unpacked), {len(rows)} inventory entries, sha256 {digest}")
        if a.keep_staging:
            keep = out / name
            if keep.exists():
                shutil.rmtree(keep)
            stage.rename(keep)
            print(f"unpacked copy: {keep}")
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    main()
