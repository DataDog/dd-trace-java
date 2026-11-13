#!/usr/bin/env bash

# Save all important reports and artifacts into (project-root)/results
# This folder will be saved by gitlab and available after test runs.

set -euo pipefail

source "$(dirname "$0")/java-utils.sh"
java_bin=$(resolve_java)

exec "$java_bin" "$(dirname "$0")/collect-result/CollectResults.java" "$@"
