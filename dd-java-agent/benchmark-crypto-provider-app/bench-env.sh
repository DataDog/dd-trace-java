# Sourced by the run-*.sh scripts. Picks the JDK and the ACCP jar for the current platform.
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
redact() { local s="${1//"$REPO_ROOT"/<repo>}"; printf '%s\n' "${s//"$HOME"/<home>}"; }
ACCP_VERSION=2.5.0

java_major() {
  "$1/bin/java" -version 2>&1 | awk -F'"' '/version/ {split($2, v, "."); print (v[1] == "1" ? v[2] : v[1]); exit}'
}

if [[ -z "${BENCH_JAVA_HOME:-}" ]]; then
  if [[ -n "${JAVA_HOME:-}" && "$(java_major "$JAVA_HOME")" -ge 21 ]]; then
    BENCH_JAVA_HOME="$JAVA_HOME"
  elif [[ -x /usr/libexec/java_home ]]; then
    BENCH_JAVA_HOME="$(/usr/libexec/java_home -v 21)"
  else
    echo "set BENCH_JAVA_HOME to a JDK 21" >&2; exit 2
  fi
fi
JAVA="$BENCH_JAVA_HOME/bin/java"
if [[ "$(java_major "$BENCH_JAVA_HOME")" -lt 21 ]]; then
  echo "BENCH_JAVA_HOME must be JDK 21+: $BENCH_JAVA_HOME" >&2; exit 2
fi

case "$(uname -s)" in
  Linux) ACCP_OS=linux ;;
  Darwin) ACCP_OS=osx ;;
  *) echo "ACCP has no build for $(uname -s)" >&2; exit 2 ;;
esac
case "$(uname -m)" in
  aarch64|arm64) ACCP_ARCH=aarch_64 ;;
  x86_64|amd64) ACCP_ARCH=x86_64 ;;
  *) echo "ACCP has no build for $(uname -m)" >&2; exit 2 ;;
esac
ACCP_JAR="$HERE/build/accp/AmazonCorrettoCryptoProvider-$ACCP_VERSION-$ACCP_OS-$ACCP_ARCH.jar"

require_built() {
  for f in "$@"; do
    [[ -e "$f" ]] || { echo "missing $f: run ./build.sh on the host first" >&2; exit 2; }
  done
}
