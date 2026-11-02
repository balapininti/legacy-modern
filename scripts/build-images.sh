#!/usr/bin/env bash
# Builds the local images: base, toolkit (Jib) and UI.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"; cd "$root"; source scripts/env.sh
cp tools/ng-extract/package.json tools/ng-extract/package-lock.json deploy/base/
docker build -q -t modtoolkit/base:1 deploy/base
mvn -B -q -Pimage -DskipTests -DskipITs package
if [ -d ui ]; then docker build -q -t modtoolkit/toolkit-ui:0.1.0-SNAPSHOT -f deploy/ui/Dockerfile .; fi
docker images --format '{{.Repository}}:{{.Tag}} {{.Size}}' | grep modtoolkit
