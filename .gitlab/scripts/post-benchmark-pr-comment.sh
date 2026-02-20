#!/usr/bin/env bash

set -euo pipefail

readonly REPORTS_DIR="${CI_PROJECT_DIR}/reports"
readonly BODY_FILE="${REPORTS_DIR}/benchmark-comment.md"
readonly BP_HEADER="${BP_HEADER:-Benchmarks}"
readonly BP_ON_DUPLICATE="${BP_ON_DUPLICATE:-replace}"

mkdir -p "${REPORTS_DIR}"

{
  if compgen -G "${REPORTS_DIR}/*/fallback_to_master.txt" >/dev/null; then
    echo "⚠️ **Warning**: Baseline build not found for merge-base commit. Comparing against the latest commit on master instead."
    echo
  fi
  echo "# Startup"
  cat "${REPORTS_DIR}/startup/comparison-baseline-vs-candidate.md"
  echo
  echo "# Load"
  cat "${REPORTS_DIR}/load/comparison-baseline-vs-candidate.md"
  echo
  echo "# Dacapo"
  cat "${REPORTS_DIR}/dacapo/comparison-baseline-vs-candidate.md"
} > "${BODY_FILE}"

if [[ -z "${UPSTREAM_BRANCH:-}" ]]; then
  cat "${BODY_FILE}"
  exit 0
fi

cat "${BODY_FILE}" | pr-commenter \
  --for-repo="${UPSTREAM_PROJECT_NAME:-$CI_PROJECT_NAME}" \
  --for-pr="${UPSTREAM_BRANCH}" \
  --header="${BP_HEADER}" \
  --on-duplicate="${BP_ON_DUPLICATE}"
