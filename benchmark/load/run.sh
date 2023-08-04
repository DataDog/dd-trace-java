#!/usr/bin/env bash
set -eu

source "${UTILS_DIR}/update-java-version.sh" 17
"${UTILS_DIR}/run-sirun-benchmarks.sh" "$@"
