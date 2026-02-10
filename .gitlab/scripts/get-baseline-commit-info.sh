#!/usr/bin/env bash

set -euo pipefail

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]:-$0}")" &>/dev/null && pwd 2>/dev/null)"
readonly OUT_ENV_FILE="${1:-${SCRIPT_DIR}/../reports/baseline-info.env}"
readonly FALLBACK_MARKER_FILE="${2:-${SCRIPT_DIR}/../reports/fallback_to_master.txt}"
readonly TARGET_BRANCH="${TARGET_BRANCH:-master}"

mkdir -p "$(dirname "${OUT_ENV_FILE}")"
rm -f "${FALLBACK_MARKER_FILE}"

if [[ -z "${CI_PROJECT_ID:-}" || -z "${CI_API_V4_URL:-}" || -z "${CI_JOB_TOKEN:-}" ]]; then
  echo "Missing CI_PROJECT_ID/CI_API_V4_URL/CI_JOB_TOKEN environment variables." >&2
  exit 1
fi

readonly PROJECT_API_URL="${CI_API_V4_URL}/projects/${CI_PROJECT_ID}"

get_pipeline_id_for_sha() {
  local sha="$1"

  curl --silent --show-error --fail \
    --header "JOB-TOKEN: ${CI_JOB_TOKEN}" \
    "${PROJECT_API_URL}/pipelines?sha=${sha}&status=success&order_by=updated_at&sort=desc&per_page=1" \
    | python3 -c 'import json,sys; data=json.load(sys.stdin); print(data[0]["id"] if data else "")'
}

get_latest_master_pipeline_id() {
  curl --silent --show-error --fail \
    --header "JOB-TOKEN: ${CI_JOB_TOKEN}" \
    "${PROJECT_API_URL}/pipelines?ref=${TARGET_BRANCH}&status=success&order_by=updated_at&sort=desc&per_page=1" \
    | python3 -c 'import json,sys; data=json.load(sys.stdin); print(data[0]["id"] if data else "")'
}

resolve_merge_base_sha() {
  if [[ -n "${CI_MERGE_REQUEST_DIFF_BASE_SHA:-}" ]]; then
    echo "${CI_MERGE_REQUEST_DIFF_BASE_SHA}"
    return
  fi

  git fetch origin "${TARGET_BRANCH}" --depth=200 >/dev/null 2>&1 || true
  git merge-base "${CI_COMMIT_SHA}" "origin/${TARGET_BRANCH}" 2>/dev/null || true
}

MERGE_BASE_SHA="$(resolve_merge_base_sha)"
BASELINE_SHA="${MERGE_BASE_SHA}"
BASELINE_PIPELINE_ID=""
BASELINE_SOURCE="merge_base"
FALLBACK_TO_MASTER="false"

if [[ -n "${MERGE_BASE_SHA}" ]]; then
  BASELINE_PIPELINE_ID="$(get_pipeline_id_for_sha "${MERGE_BASE_SHA}")"
fi

if [[ -z "${BASELINE_PIPELINE_ID}" ]]; then
  FALLBACK_TO_MASTER="true"
  BASELINE_SOURCE="master"
  BASELINE_SHA="$(git rev-parse "origin/${TARGET_BRANCH}" 2>/dev/null || true)"
  BASELINE_PIPELINE_ID="$(get_latest_master_pipeline_id)"
fi

if [[ -z "${BASELINE_PIPELINE_ID}" ]]; then
  echo "Failed to resolve a baseline pipeline id." >&2
  exit 1
fi

if [[ "${FALLBACK_TO_MASTER}" == "true" ]]; then
  echo "fallback_to_master=true" > "${FALLBACK_MARKER_FILE}"
fi

cat > "${OUT_ENV_FILE}" <<EOF
MERGE_BASE_SHA=${MERGE_BASE_SHA}
BASELINE_SHA=${BASELINE_SHA}
BASELINE_PIPELINE_ID=${BASELINE_PIPELINE_ID}
BASELINE_SOURCE=${BASELINE_SOURCE}
FALLBACK_TO_MASTER=${FALLBACK_TO_MASTER}
EOF

echo "Baseline resolved:"
cat "${OUT_ENV_FILE}"
