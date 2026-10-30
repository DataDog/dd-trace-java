#!/usr/bin/env bash
# JMH, no agent: JDK providers vs ACCP at position 1. See README.md for example runs.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/bench-env.sh"
JMH_JAR="$HERE/build/libs/crypto-bench-jmh.jar"
require_built "$JMH_JAR" "$ACCP_JAR"
OUT="${JMH_OUT:-$HERE/results/jmh-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$OUT"
{
  echo "date=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "host=$(uname -srm) cpus=$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo ?)"
  echo "java=$("$JAVA" -version 2>&1 | head -1)"
  echo "accp=$(basename "$ACCP_JAR")"
  echo "args=$*"
} > "$OUT/env.txt"
cat "$OUT/env.txt"
"$JAVA" -cp "$JMH_JAR:$ACCP_JAR" org.openjdk.jmh.Main -rf json -rff "$OUT/jmh-result.json" "$@" 2>&1 | tee "$OUT/jmh-output.txt"
echo "== results in $OUT"
