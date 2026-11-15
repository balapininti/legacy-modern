#!/usr/bin/env bash
# A native PostgreSQL for development, no containers: the server validate-corpora.py, crosscheck-corpora.py and
# mcp-probe.py talk to (through scripts/evalpg.py), and the throwaway one ui/e2e/stack.sh starts.
#
#   scripts/dev-pg.sh start|stop|status|destroy|psql [psql args]
#
# start   initdb on first use (scram-sha-256, listens on 127.0.0.1 only, superuser and a database of the same name),
#         then starts the server. Safe to repeat. Data survives stop.
# stop    stops the server (fast shutdown). The data stays.
# status  says whether it runs, and the JDBC URL.
# destroy stops it and deletes its data directory.
# psql    runs this installation's psql against it (database $TOOLKIT_EVAL_PG_USER unless -d is given).
#
# PostgreSQL 17 with pgvector: TOOLKIT_TEST_PG_HOME (the folder holding bin/pg_ctl), else the parent of
# `pg_config --bindir`, else /opt/homebrew/opt/postgresql@17. macOS: brew install postgresql@17 pgvector.
# Debian/Ubuntu (PGDG): apt install postgresql-17 postgresql-17-pgvector, TOOLKIT_TEST_PG_HOME=/usr/lib/postgresql/17.
# The vector extension itself is created by the toolkit's migrations.
#
# Settings (defaults match scripts/evalpg.py):
#   TOOLKIT_DEV_PG_DIR        data and log directory   (.cache/dev-pg in this repository)
#   TOOLKIT_EVAL_PG_PORT      port                     (55433)
#   TOOLKIT_EVAL_PG_USER      superuser and database   (toolkit)
#   TOOLKIT_EVAL_PG_PASSWORD  password                 (toolkit)
#   TOOLKIT_DEV_PG_MAX_CONNECTIONS                     (200)
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIR="${TOOLKIT_DEV_PG_DIR:-$root/.cache/dev-pg}"
PORT="${TOOLKIT_EVAL_PG_PORT:-55433}"
PGUSER_="${TOOLKIT_EVAL_PG_USER:-toolkit}"
PASSWORD="${TOOLKIT_EVAL_PG_PASSWORD:-toolkit}"
MAXCONN="${TOOLKIT_DEV_PG_MAX_CONNECTIONS:-200}"
DATA="$DIR/data"
LOG="$DIR/postgres.log"

pg_home() {
  if [ -n "${TOOLKIT_TEST_PG_HOME:-}" ]; then echo "$TOOLKIT_TEST_PG_HOME"; return; fi
  local bindir
  if bindir="$(pg_config --bindir 2>/dev/null)" && [ -x "$bindir/pg_ctl" ]; then dirname "$bindir"; return; fi
  echo /opt/homebrew/opt/postgresql@17
}
HOME_="$(pg_home)"
BIN="$HOME_/bin"
[ -x "$BIN/pg_ctl" ] && [ -x "$BIN/initdb" ] || {
  echo "no PostgreSQL at $HOME_ (no bin/pg_ctl): install PostgreSQL 17 with pgvector and set TOOLKIT_TEST_PG_HOME" >&2; exit 2; }

running() { [ -f "$DATA/postmaster.pid" ] && "$BIN/pg_ctl" -D "$DATA" status >/dev/null 2>&1; }

start() {
  if [ ! -f "$DATA/PG_VERSION" ]; then
    mkdir -p "$DIR"; chmod 700 "$DIR"
    local pw; pw="$(mktemp "$DIR/pw.XXXXXX")"
    printf '%s\n' "$PASSWORD" > "$pw"
    "$BIN/initdb" -D "$DATA" -U "$PGUSER_" --pwfile="$pw" --auth=scram-sha-256 --encoding=UTF8 --no-locale >"$DIR/initdb.log" 2>&1 \
      || { rm -f "$pw"; echo "initdb failed, see $DIR/initdb.log" >&2; exit 1; }
    rm -f "$pw"
    cat >> "$DATA/postgresql.conf" <<CONF
listen_addresses = '127.0.0.1'
port = $PORT
max_connections = $MAXCONN
unix_socket_directories = ''
CONF
  fi
  if running; then echo "dev-pg already running on 127.0.0.1:$PORT"; return; fi
  # A postmaster.pid left by a crash: pg_ctl start refuses only when that pid is a live postgres, so it is safe to try.
  if (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; then echo "port $PORT is already in use: set TOOLKIT_EVAL_PG_PORT" >&2; exit 1; fi
  "$BIN/pg_ctl" -D "$DATA" -l "$LOG" -o "-p $PORT" -w -t 60 start >/dev/null || { echo "postgres did not start, see $LOG" >&2; exit 1; }
  PGPASSWORD="$PASSWORD" "$BIN/psql" -h 127.0.0.1 -p "$PORT" -U "$PGUSER_" -d postgres -qAtc "select 1 from pg_database where datname='$PGUSER_'" | grep -q 1 \
    || PGPASSWORD="$PASSWORD" "$BIN/psql" -h 127.0.0.1 -p "$PORT" -U "$PGUSER_" -d postgres -qc "CREATE DATABASE \"$PGUSER_\""
  echo "dev-pg up: jdbc:postgresql://127.0.0.1:$PORT/$PGUSER_ (user $PGUSER_), data $DATA"
}

stop() { if running; then "$BIN/pg_ctl" -D "$DATA" -m fast -w stop >/dev/null; fi; }

case "${1:-}" in
  start) start ;;
  stop) stop ;;
  status) if running; then echo "running: jdbc:postgresql://127.0.0.1:$PORT/$PGUSER_ ($DATA)"; else echo "stopped ($DATA)"; exit 3; fi ;;
  destroy) stop; rm -rf "$DIR" ;;
  psql) shift; PGPASSWORD="$PASSWORD" exec "$BIN/psql" -h 127.0.0.1 -p "$PORT" -U "$PGUSER_" -d "$PGUSER_" "$@" ;;
  *) echo "usage: dev-pg.sh start|stop|status|destroy|psql [args]" >&2; exit 2 ;;
esac
