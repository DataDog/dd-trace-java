#!/usr/bin/env bash

set -euo pipefail

readonly REPORTS_DIR="${CI_PROJECT_DIR}/reports"
readonly MARKER="<!-- dd-trace-java-benchmarks-comment -->"
readonly BODY_FILE="${REPORTS_DIR}/benchmark-comment.md"

mkdir -p "${REPORTS_DIR}"

{
  echo "${MARKER}"
  echo
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

if [[ -z "${GITHUB_REPOSITORY:-}" ]]; then
  export GITHUB_REPOSITORY="DataDog/${UPSTREAM_PROJECT_NAME:-dd-trace-java}"
fi

if [[ -z "${GITHUB_TOKEN:-}" ]]; then
  echo "GITHUB_TOKEN is required to post benchmark comments." >&2
  exit 1
fi

gh auth login --with-token < <(printf '%s' "${GITHUB_TOKEN}") >/dev/null 2>&1 || true
COMMENT_BODY="$(cat "${BODY_FILE}")"

readonly PR_NUMBER="$(
  gh api "repos/${GITHUB_REPOSITORY}/pulls" \
    -f state=open \
    -f head="DataDog:${UPSTREAM_BRANCH}" \
    --jq '.[0].number // ""'
)"

if [[ -z "${PR_NUMBER}" ]]; then
  echo "No open PR found for branch '${UPSTREAM_BRANCH}'. Printing report instead."
  cat "${BODY_FILE}"
  exit 0
fi

readonly COMMENT_ID="$(
  gh api "repos/${GITHUB_REPOSITORY}/issues/${PR_NUMBER}/comments" --paginate \
    --jq ".[] | select(.body | contains(\"${MARKER}\")) | .id" \
    | head -n 1
)"

if [[ -n "${COMMENT_ID}" ]]; then
  gh api --method PATCH "repos/${GITHUB_REPOSITORY}/issues/comments/${COMMENT_ID}" \
    -f body="${COMMENT_BODY}" >/dev/null
  echo "Updated existing benchmark comment (${COMMENT_ID}) on PR #${PR_NUMBER}."
else
  gh api --method POST "repos/${GITHUB_REPOSITORY}/issues/${PR_NUMBER}/comments" \
    -f body="${COMMENT_BODY}" >/dev/null
  echo "Posted benchmark comment on PR #${PR_NUMBER}."
fi
