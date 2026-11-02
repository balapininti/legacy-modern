#!/usr/bin/env bash
# Rebuilds the vendored parsers from pinned upstream commits into third_party/repo.
# ProLeap (MIT) and Koopa (BSD) are not published to Maven Central. See docs/adr/0001.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"; source "$root/scripts/env.sh"
src="$root/third_party/src"; mkdir -p "$src"
PROLEAP_SHA=b045d8093aab535a21deee4b4da4f3133d30d028
rm -rf "$src/proleap"; git clone -q https://github.com/uwol/proleap-cobol-parser "$src/proleap"
git -C "$src/proleap" checkout -q "$PROLEAP_SHA"
git -C "$src/proleap" apply "$root/third_party/patches/proleap-v2.4.0-antlr-4.13.2.patch"
(cd "$src/proleap" && mvn -q -B -DskipTests clean package)
mvn -q -B install:install-file -Dfile="$src/proleap/target/cobol85parser-2.4.0.jar" \
  -DgroupId=com.modtoolkit.vendor -DartifactId=proleap-cobol-parser -Dversion=2.4.0-antlr4.13.2 -Dpackaging=jar \
  -DlocalRepositoryPath="$root/third_party/repo" -DcreateChecksum=true
