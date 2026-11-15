# Source this for a local dev shell: pins JDK 21 and Maven from .tools/ when present.
_root="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd)"
for d in "$_root"/.tools/jdk-21*; do [ -d "$d/Contents/Home" ] && export JAVA_HOME="$d/Contents/Home"; [ -x "$d/bin/java" ] && export JAVA_HOME="$d"; done
[ -n "${JAVA_HOME:-}" ] && export PATH="$JAVA_HOME/bin:$PATH"
for d in "$_root"/.tools/apache-maven-*; do [ -d "$d" ] && export PATH="$d/bin:$PATH"; done
