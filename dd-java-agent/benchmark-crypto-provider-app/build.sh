#!/usr/bin/env bash
# Builds everything the run-*.sh scripts need. Run on the host.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
"$HERE/../../gradlew" -p "$HERE" -q benchAll "$@"
