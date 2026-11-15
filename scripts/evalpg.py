"""
The native PostgreSQL the evaluation scripts (validate-corpora, crosscheck-corpora, mcp-probe) use, as started by
scripts/dev-pg.sh. Settings come from the environment, with the same defaults as dev-pg.sh:

  TOOLKIT_EVAL_PG_HOST (127.0.0.1)  TOOLKIT_EVAL_PG_PORT (55433)
  TOOLKIT_EVAL_PG_USER (toolkit)    TOOLKIT_EVAL_PG_PASSWORD (toolkit)

psql is taken from TOOLKIT_TEST_PG_HOME/bin when set, else from the PATH.
"""
import os, shutil, subprocess

HOST = os.environ.get("TOOLKIT_EVAL_PG_HOST", "127.0.0.1")
PORT = os.environ.get("TOOLKIT_EVAL_PG_PORT", "55433")
USER = os.environ.get("TOOLKIT_EVAL_PG_USER", "toolkit")
PASSWORD = os.environ.get("TOOLKIT_EVAL_PG_PASSWORD", "toolkit")


def psql_binary():
    home = os.environ.get("TOOLKIT_TEST_PG_HOME")
    if home:
        for name in ("psql", "psql.exe"):
            p = os.path.join(home, "bin", name)
            if os.path.isfile(p):
                return p
    p = shutil.which("psql")
    if not p:
        raise SystemExit("psql not found: set TOOLKIT_TEST_PG_HOME or put PostgreSQL's bin on the PATH")
    return p


def psql(db, *args):
    """Runs psql against db with the given arguments; returns the CompletedProcess."""
    env = dict(os.environ, PGPASSWORD=PASSWORD)
    return subprocess.run([psql_binary(), "-h", HOST, "-p", PORT, "-U", USER, "-d", db, *args],
                          capture_output=True, text=True, env=env)


def query(db, sql):
    """Unaligned, tuples-only, |-separated output of one statement, stripped."""
    return psql(db, "-At", "-F", "|", "-c", sql).stdout.strip()


def toolkit_env(db, **extra):
    """The environment for a toolkit JVM pointed at db."""
    return dict(os.environ, TOOLKIT_DB_URL=f"jdbc:postgresql://{HOST}:{PORT}/{db}", TOOLKIT_DB_USER=USER,
                TOOLKIT_DB_PASSWORD=PASSWORD, **extra)
