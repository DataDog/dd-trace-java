#!/usr/bin/env bash

set -euo pipefail

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]:-$0}")" &>/dev/null && pwd 2>/dev/null)"
readonly OUTPUT_DIR="${1:-${SCRIPT_DIR}/../reports}"
readonly OUT_ENV_FILE="${OUTPUT_DIR}/baseline-info.env"
readonly FALLBACK_MARKER_FILE="${OUTPUT_DIR}/fallback_to_master.txt"
readonly TARGET_BRANCH="${TARGET_BRANCH:-master}"

mkdir -p "${OUTPUT_DIR}"
rm -f "${FALLBACK_MARKER_FILE}"

if [[ -z "${CI_PROJECT_ID:-}" || -z "${CI_API_V4_URL:-}" || -z "${CI_JOB_TOKEN:-}" ]]; then
  echo "Missing CI_PROJECT_ID/CI_API_V4_URL/CI_JOB_TOKEN environment variables." >&2
  exit 1
fi

readonly PROJECT_API_URL="${CI_API_V4_URL}/projects/${CI_PROJECT_ID}"

log_debug() {
  echo "[baseline-debug] $*" >&2
}

response_snippet() {
  local response="$1"
  echo "${response}" | tr '\n' ' ' | sed 's/[[:space:]]\+/ /g' | cut -c1-220
}

get_pipeline_id_for_commit() {
  local commit_sha="$1"
  local api_url="${PROJECT_API_URL}/repository/commits/${commit_sha}"
  local response pipeline_id

  log_debug "query commit endpoint: ${api_url}"
  response="$(curl --request GET --silent --show-error --header "JOB-TOKEN: ${CI_JOB_TOKEN}" "${api_url}" || true)"
  pipeline_id="$(echo "${response}" | grep -o '"last_pipeline"[^}]*"id":[0-9]*' | grep -o '[0-9]*$' | head -1 || true)"
  if [[ -n "${pipeline_id}" && "${pipeline_id}" != "null" ]]; then
    log_debug "found pipeline_id=${pipeline_id} for commit_sha=${commit_sha}"
    echo "${pipeline_id}"
    return 0
  fi

  log_debug "no last_pipeline.id for commit_sha=${commit_sha}; response='$(response_snippet "${response}")'"
  return 1
}

get_latest_pipeline_id_for_branch() {
  local branch="$1"
  local api_url="${PROJECT_API_URL}/pipelines?ref=${branch}&order_by=id&sort=desc&per_page=1"
  local response pipeline_id

  log_debug "query pipelines endpoint: ${api_url}"
  response="$(curl --request GET --silent --show-error --header "JOB-TOKEN: ${CI_JOB_TOKEN}" "${api_url}" || true)"
  pipeline_id="$(echo "${response}" | grep -o '"id":[0-9]*' | head -1 | grep -o '[0-9]*' || true)"
  if [[ -n "${pipeline_id}" ]]; then
    log_debug "found latest pipeline_id=${pipeline_id} for branch=${branch}"
  else
    log_debug "no pipeline found for branch=${branch}; response='$(response_snippet "${response}")'"
  fi
  echo "${pipeline_id}"
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

log_debug "resolved merge_base_sha='${MERGE_BASE_SHA:-<empty>}' target_branch='${TARGET_BRANCH}'"

if [[ -n "${MERGE_BASE_SHA}" ]]; then
  BASELINE_PIPELINE_ID="$(get_pipeline_id_for_commit "${MERGE_BASE_SHA}" || true)"
fi

if [[ -z "${BASELINE_PIPELINE_ID}" ]]; then
  FALLBACK_TO_MASTER="true"
  BASELINE_SOURCE="${TARGET_BRANCH}"
  log_debug "merge-base pipeline not found; falling back to branch='${TARGET_BRANCH}'"

  BASELINE_SHA="$(git rev-parse "origin/${TARGET_BRANCH}" 2>/dev/null || true)"
  log_debug "resolved branch sha from local git: '${BASELINE_SHA:-<empty>}'"
  if [[ -n "${BASELINE_SHA}" ]]; then
    BASELINE_PIPELINE_ID="$(get_pipeline_id_for_commit "${BASELINE_SHA}" || true)"
  fi

  # Final fallback: use latest branch pipeline
  if [[ -z "${BASELINE_PIPELINE_ID}" ]]; then
    BASELINE_PIPELINE_ID="$(get_latest_pipeline_id_for_branch "${TARGET_BRANCH}" || true)"
  fi
fi

if [[ -z "${BASELINE_PIPELINE_ID}" ]]; then
  echo "Failed to resolve a baseline pipeline id." >&2
  echo "merge_base_sha=${MERGE_BASE_SHA:-<empty>}, baseline_sha=${BASELINE_SHA:-<empty>}" >&2
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
