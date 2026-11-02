#!/usr/bin/env bash
# Fetches the pinned golden corpora into .cache/corpora. Pins change only on purpose: snapshots depend on them.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"; dest="$root/.cache/corpora"; mkdir -p "$dest"
fetch() { local name="$1" url="$2" sha="$3"
  if [ ! -d "$dest/$name/.git" ]; then git init -q "$dest/$name"; git -C "$dest/$name" remote add origin "$url"; fi
  if [ "$(git -C "$dest/$name" rev-parse HEAD 2>/dev/null || true)" != "$sha" ]; then
    git -C "$dest/$name" fetch -q --depth 1 origin "$sha"; git -C "$dest/$name" checkout -q FETCH_HEAD; fi
  echo "$name $sha"; }
fetch carddemo https://github.com/aws-samples/aws-mainframe-modernization-carddemo 59cc6c2fd7ebd7ef7925cad552a01a4b8b6e4d5e

# The owner's validation set, fetched with --all. CBSA's default branch is empty since it was archived; this commit is on July2024Refresh.
if [ "${1:-}" = "--all" ]; then
  fetch bankdemo https://github.com/RocketSoftwareCOBOLandMainframe/BankDemo 435d8f603fe20328adc51099d18155866299f7d4
  fetch bank-of-z https://github.com/IBM/Bank-of-Z 35415c89da4a3d1e34e286d950477cac0bb43968
  fetch cbsa https://github.com/cicsdev/cics-banking-sample-application-cbsa 417334533178ab6e753cc64b0e0e5cf0b4952704
  fetch genapp https://github.com/cicsdev/cics-genapp f6f3f4b2580d31b7d8dcc31ce3e3676f4cceaaaa
  fetch dsf https://github.com/navikt/DSF faade4961e316c89e8c312456bae411c63f1c482
  fetch taxe-fonciere https://github.com/etalab/taxe-fonciere 6bd40b2d78eaeb3f2635fbd7a9eb1f952d0739ba
  fetch bricks https://github.com/moshix/BRICKS_TS de1d30dfda2c2163e73bbbfb4dcdeb418dbe9846
  fetch cobol-unit-test https://github.com/neopragma/cobol-unit-test 8e2c04fc8cda62fb54df8042c141b9ee43abdc16
  fetch cobol-course https://github.com/openmainframeproject/cobol-programming-course 780b1c9624e066b9d784a738896e493282c5fbf4
fi
