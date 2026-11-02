#!/usr/bin/env bash
# The M8 proof: production build, a freshly seeded stack, the Playwright test in headless Chromium, and a clean stop.
#   TOOLKIT_CHECKOUT=/path/to/main/checkout npm run e2e     (TOOLKIT_CHECKOUT only when this is a worktree without .tools and .cache)
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
npm run build
trap 'bash e2e/stack.sh stop' EXIT
bash e2e/stack.sh start
npx playwright test "$@"
