#!/usr/bin/env bash
# Starts and stops a throwaway seeded stack for the UI e2e proof: its own Postgres container, the real toolkit jar
# (discover, replayed knowledge pass, index, capture import, tokens, serve) and a static server for the production UI.
#
#   TOOLKIT_CHECKOUT=/path/to/main/checkout e2e/stack.sh start|stop|status
#
# TOOLKIT_CHECKOUT is where the gitignored scripts/env.sh tools and .cache/corpora live; it defaults to this repository.
# Everything it creates is under e2e/.stack (gitignored) and in the container named below. Nothing else is touched.
set -euo pipefail

UI_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="$(cd "$UI_DIR/.." && pwd)"
CHECKOUT="${TOOLKIT_CHECKOUT:-$REPO}"
STATE="$UI_DIR/e2e/.stack"
PG_NAME="toolkit-ui-e2e-pg"
PG_PASSWORD="ui-e2e-only"
ADMIN_PASSWORD="ui-e2e-admin-only"
JAR="$REPO/cli/target/toolkit.jar"
CORPUS="${TOOLKIT_CORPUS:-$CHECKOUT/.cache/corpora/carddemo}"

# shellcheck disable=SC1091
source "$CHECKOUT/scripts/env.sh"

free_port() { node -e 'const s=require("net").createServer();s.listen(0,"127.0.0.1",()=>{console.log(s.address().port);s.close()})'; }

stop() {
  for f in ui api; do
    if [ -f "$STATE/$f.pid" ]; then
      kill "$(cat "$STATE/$f.pid")" 2>/dev/null || true
      rm -f "$STATE/$f.pid"
    fi
  done
  docker rm -f "$PG_NAME" >/dev/null 2>&1 || true
  rm -f "$STATE/stack.env"
}

toolkit() { java -jar "$JAR" "$@"; }

start() {
  [ -f "$JAR" ] || { echo "no $JAR: run mvn -B -q -DskipTests -DskipITs package first" >&2; exit 2; }
  [ -d "$CORPUS/app/cbl" ] || { echo "no corpus at $CORPUS: set TOOLKIT_CHECKOUT or TOOLKIT_CORPUS" >&2; exit 2; }
  [ -f "$UI_DIR/dist/ui/browser/index.html" ] || { echo "no UI build: run npm run build first" >&2; exit 2; }
  stop
  rm -rf "$STATE"
  mkdir -p "$STATE"
  chmod 700 "$STATE"

  echo "[stack] postgres"
  docker run -d --name "$PG_NAME" -e POSTGRES_PASSWORD="$PG_PASSWORD" -e POSTGRES_USER=toolkit -e POSTGRES_DB=toolkit \
    -p 127.0.0.1::5432 pgvector/pgvector:pg16 -c max_connections=200 >/dev/null
  until docker exec "$PG_NAME" psql -U toolkit -d toolkit -c 'select 1' >/dev/null 2>&1; do sleep 1; done
  local pg_port
  pg_port="$(docker port "$PG_NAME" 5432/tcp | head -1 | sed 's/.*://')"
  export TOOLKIT_DB_URL="jdbc:postgresql://127.0.0.1:$pg_port/toolkit" TOOLKIT_DB_USER=toolkit TOOLKIT_DB_PASSWORD="$PG_PASSWORD"

  echo "[stack] target repository with a commit citing CBACT04C"
  local target="$STATE/target-sample"
  cp -R "$REPO/testdata/target-sample" "$target"
  git -C "$target" init -q -b main
  commit() { git -C "$target" -c user.name=e2e -c user.email=e2e@example.com commit -q -m "$1"; }
  git -C "$target" add README.md
  commit "chore: start"
  git -C "$target" add src/main/java/com/acme/card/batch src/main/java/com/acme/card/entity src/main/java/com/acme/card/repo
  commit "Port CBACT04C interest calculation to Spring Batch"
  git -C "$target" add .
  commit "Account view screen"

  local yaml="$STATE/toolkit.yaml"
  cat > "$yaml" <<YAML
client: x
source:
  root: $CORPUS
llm:
  provider: openai-compatible
  chat_model: gpt-oss-32k:latest
YAML

  cd "$REPO"
  echo "[stack] discover"
  toolkit discover --root "$CORPUS" --threads 8 --target "$target" > "$STATE/discover.log" 2>&1
  echo "[stack] knowledge run (replay: nothing is sent anywhere)"
  local rc=0
  toolkit knowledge run -c "$yaml" --replay-from testdata/llm-recordings --programs CBACT04C,COSGN00C,CBTRN02C > "$STATE/knowledge.log" 2>&1 || rc=$?
  # 3 means some unit had no recording, which is expected here.
  if [ "$rc" != 0 ] && [ "$rc" != 3 ]; then echo "knowledge run exited $rc, see $STATE/knowledge.log" >&2; exit 1; fi
  echo "[stack] index"
  toolkit index > "$STATE/index.log" 2>&1
  echo "[stack] capture import"
  toolkit capture import --dir testdata/captures/CBACT04C --encoding utf-8 > "$STATE/capture.log" 2>&1

  echo "[stack] a candidate deviation, as the MCP tool record_deviation or tests report would leave it"
  docker exec -i "$PG_NAME" psql -q -U toolkit -d toolkit >/dev/null <<'SQL'
INSERT INTO deviation (legacy_ref, modern_ref, change, reason, comparison_rule, status) VALUES
 ('{"program":"CBACT04C","field":"TRAN-ORIG-TS"}', '{"path":"src/main/java/com/acme/card/batch/InterestCalculator.java"}',
  'Timestamp comes from the run clock', 'Run time is expected to differ between the mainframe and the JVM', '{"ignore":true}', 'candidate'),
 ('{"program":"CBACT04C","field":"TRAN-PROC-TS"}', '{"path":"src/main/java/com/acme/card/batch/InterestCalculator.java"}',
  'Processing timestamp comes from the run clock', 'Run time is expected to differ between the mainframe and the JVM', '{"ignore":true}', 'candidate');
SQL

  local sme_token dev_token api_port ui_port
  sme_token="$(toolkit token --user sme1 --role sme-reviewer 2>/dev/null | tail -1)"
  dev_token="$(toolkit token --user dev1 --role developer 2>/dev/null | tail -1)"

  echo "[stack] serve"
  api_port="$(free_port)"
  TOOLKIT_ADMIN_PASSWORD="$ADMIN_PASSWORD" TOOLKIT_API_PORT="$api_port" TOOLKIT_CONFIG="$yaml" \
    nohup java -jar "$JAR" serve > "$STATE/api.log" 2>&1 &
  echo $! > "$STATE/api.pid"
  for _ in $(seq 1 120); do
    curl -fs "http://127.0.0.1:$api_port/actuator/health" >/dev/null 2>&1 && break
    sleep 1
  done
  curl -fs "http://127.0.0.1:$api_port/actuator/health" >/dev/null || { echo "api did not start, see $STATE/api.log" >&2; exit 1; }

  ui_port="$(free_port)"
  nohup node "$UI_DIR/e2e/serve.mjs" "$UI_DIR/dist/ui/browser" "$ui_port" "$api_port" > "$STATE/ui.log" 2>&1 &
  echo $! > "$STATE/ui.pid"
  for _ in $(seq 1 30); do
    curl -fs "http://127.0.0.1:$ui_port/" >/dev/null 2>&1 && break
    sleep 0.5
  done

  cat > "$STATE/stack.env" <<ENV
UI_URL=http://127.0.0.1:$ui_port
API_URL=http://127.0.0.1:$api_port
ADMIN_PASSWORD=$ADMIN_PASSWORD
SME_TOKEN=$sme_token
DEV_TOKEN=$dev_token
ENV
  chmod 600 "$STATE/stack.env"
  echo "[stack] up: UI http://127.0.0.1:$ui_port API http://127.0.0.1:$api_port"
}

case "${1:-}" in
  start) start ;;
  stop) stop ;;
  status) docker ps --filter "name=$PG_NAME" --format '{{.Names}} {{.Status}} {{.Ports}}'; [ -f "$STATE/stack.env" ] && grep -E '^(UI|API)_URL' "$STATE/stack.env" ;;
  *) echo "usage: stack.sh start|stop|status" >&2; exit 2 ;;
esac
