#!/usr/bin/env bash
# CI's install check: from the Compose bundle to a first MCP answer, with nothing but what ships.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"; cd "$root"
work="$(mktemp -d "${TMPDIR:-/tmp}/toolkit-smoke.XXXXXX")"; port="${SMOKE_PORT:-18443}"
cp -R deploy/compose "$work/compose"; cd "$work/compose"
trap 'docker compose --profile cli down -v >/dev/null 2>&1 || true; rm -rf "$work"' EXIT
mkdir -p "$work/export"; cp "$root/testdata/smoke/"* "$work/export/"
cat > .env <<ENV
TOOLKIT_DB_PASSWORD=$(openssl rand -hex 16)
TOOLKIT_ADMIN_PASSWORD=$(openssl rand -hex 16)
TOOLKIT_SOURCE=$work/export
TOOLKIT_HTTPS_PORT=$port
ENV
cp config/toolkit.yaml.example config/toolkit.yaml
./gen-tls.sh localhost
docker compose up -d
./toolkit doctor -c /config/toolkit.yaml
./toolkit discover -c /config/toolkit.yaml | tee discover.json | grep -q '"parsed" : 3'
./toolkit index -c /config/toolkit.yaml
token="$(./toolkit token --user smoke --role developer | tail -1)"
init='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"smoke","version":"1"}}}'
url="https://localhost:$port/mcp"; h=(-sk -H "Authorization: Bearer $token" -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream')
for i in $(seq 1 60); do code="$(curl -sk -o /dev/null -w '%{http_code}' "https://localhost:$port/" || true)"; [ "$code" = 200 ] && break; sleep 2; done
for i in $(seq 1 60); do session="$(curl "${h[@]}" -D - -o /dev/null -X POST "$url" -d "$init" | tr -d '\r' | awk 'tolower($1)=="mcp-session-id:"{print $2}')"; [ -n "$session" ] && break; sleep 2; done
[ -n "$session" ]
curl "${h[@]}" -o /dev/null -H "Mcp-Session-Id: $session" -X POST "$url" -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'
answer="$(curl "${h[@]}" -N -m 30 -H "Mcp-Session-Id: $session" -X POST "$url" -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"who_calls","arguments":{"program":"SMOKEB"}}}')"
echo "$answer" | grep -q 'SMOKEA' || { echo "unexpected answer: $answer"; exit 1; }
test "$(curl -s -o /dev/null -w '%{http_code}' -k -X POST "$url" -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' -d "$init")" = 401
echo "smoke test passed"
