#!/usr/bin/env bash

# Save all important reports and artifacts into (project-root)/results
# This folder will be saved by gitlab and available after test runs.

set -euo pipefail

java_bin="${JAVA_25_HOME:-}"
if [[ -n "$java_bin" ]]; then
  java_bin="$java_bin/bin/java"
else
  java_bin="java"
fi

exec "$java_bin" "$(dirname "$0")/collect-result/CollectResults.java" "$@"
