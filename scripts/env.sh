# Source this for a local dev shell: pins JDK 21 and Maven from .tools/ when present.
# Safe to source from a script running under `set -e` where .tools/ does not exist (CI): every test is an `if`, and
# the file ends with `true`, so sourcing never returns a non-zero status.
_root="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd)"
for d in "$_root"/.tools/jdk-21*; do
  if [ -d "$d/Contents/Home" ]; then export JAVA_HOME="$d/Contents/Home"; fi
  if [ -x "$d/bin/java" ]; then export JAVA_HOME="$d"; fi
done
if [ -n "${JAVA_HOME:-}" ]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
for d in "$_root"/.tools/apache-maven-*; do
  if [ -d "$d" ]; then export PATH="$d/bin:$PATH"; fi
done
unset _root d
true
