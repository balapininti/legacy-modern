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
