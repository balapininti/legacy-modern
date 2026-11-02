#!/usr/bin/env bash
# Runs named integration tests in a module and its dependencies: scripts/it.sh <module> <TestNames> [extra mvn args]
set -uo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"; source "$root/scripts/env.sh"; cd "$root"
mod="$1"; tests="$2"; shift 2
mvn -B verify -pl "$mod" -am -Dit.test="$tests" -Dtest=NoSuchTestClass -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false "$@"
