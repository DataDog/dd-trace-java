#!/usr/bin/env bash

set -x

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]:-$0}")" &>/dev/null && pwd 2>/dev/null)"
source "${SCRIPT_DIR}/config-benchmark-env.sh"

message "$(basename "$0") started"
if [[ -z "${UPSTREAM_BRANCH}" ]]; then
  cat "${ARTIFACTS_DIR}/comparison-baseline-vs-candidate.md"
else
  cat "${ARTIFACTS_DIR}/comparison-baseline-vs-candidate.md" | pr-commenter --for-repo="${UPSTREAM_PROJECT_NAME}" --for-pr="${UPSTREAM_BRANCH}" --header="Debugger benchmarks" --on-duplicate="replace"
fi

message "$(basename "$0") finished"
