#!/usr/bin/env bash

set -euo pipefail

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]:-$0}")" &>/dev/null && pwd 2>/dev/null)"
readonly OUTPUT_DIR="${1:-${SCRIPT_DIR}/../reports}"
readonly OUT_ENV_FILE="${OUTPUT_DIR}/baseline-info.env"
readonly FALLBACK_MARKER_FILE="${OUTPUT_DIR}/fallback_to_master.txt"
readonly TARGET_BRANCH="${TARGET_BRANCH:-master}"

mkdir -p "${OUTPUT_DIR}"
rm -f "${FALLBACK_MARKER_FILE}"

if [[ -z "${CI_PROJECT_ID:-}" || -z "${CI_API_V4_URL:-}" ]]; then
  echo "Missing CI_PROJECT_ID/CI_API_V4_URL environment variables." >&2
  exit 1
fi

readonly PROJECT_API_URL="${CI_API_V4_URL}/projects/${CI_PROJECT_ID}"
readonly BTI_TOKEN_URL="https://bti-ci-api.us1.ddbuild.io/internal/ci/gitlab/token?owner=DataDog&repository=apm-reliability/dd-trace-java"

get_private_token() {
  local auth_header bti_response http_status response_body private_token

  if ! auth_header="$(authanywhere --audience sdm 2>&1)"; then
    echo "Failed to get authanywhere token: ${auth_header}" >&2
    return 1
  fi

  bti_response="$(
    curl -w "\nHTTP_STATUS:%{http_code}" --silent --show-error \
      --header "${auth_header}" \
      "${BTI_TOKEN_URL}" 2>&1
  )"
  http_status="$(echo "${bti_response}" | grep "HTTP_STATUS:" | sed 's/HTTP_STATUS://')"
  response_body="$(echo "${bti_response}" | sed '/HTTP_STATUS:/d')"

  if [[ "${http_status}" != "200" ]]; then
    echo "BTI token request failed with status ${http_status}." >&2
    return 1
  fi

  private_token="$(echo "${response_body}" | grep -o '"token":"[^"]*"' | sed 's/"token":"\([^"]*\)"/\1/')"
  if [[ -z "${private_token}" ]]; then
    echo "Failed to parse private token from BTI response." >&2
    return 1
  fi

  echo "${private_token}"
}

get_pipeline_id_for_commit() {
  local commit_sha="$1"
  local api_url="${PROJECT_API_URL}/repository/commits/${commit_sha}"
  local response pipeline_id private_token

  private_token="$(get_private_token)" || return 1
  response="$(curl --request GET --silent --show-error --header "PRIVATE-TOKEN: ${private_token}" "${api_url}" || true)"
  pipeline_id="$(echo "${response}" | grep -o '"last_pipeline"[^}]*"id":[0-9]*' | grep -o '[0-9]*$' | head -1 || true)"
  if [[ -n "${pipeline_id}" && "${pipeline_id}" != "null" ]]; then
    echo "Pipeline ID for commit ${commit_sha}: ${pipeline_id}"
    return 0
  fi

  return 1
}

get_latest_pipeline_id_for_branch() {
  local branch="$1"
  local api_url="${PROJECT_API_URL}/pipelines?ref=${branch}&order_by=id&sort=desc&per_page=1"
  local response pipeline_id private_token

  private_token="$(get_private_token)" || return 1
  response="$(curl --request GET --silent --show-error --header "PRIVATE-TOKEN: ${private_token}" "${api_url}" || true)"
  pipeline_id="$(echo "${response}" | grep -o '"id":[0-9]*' | head -1 | grep -o '[0-9]*' || true)"
  echo "Pipeline ID for branch ${branch}: ${pipeline_id}"
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
  BASELINE_PIPELINE_ID="$(get_pipeline_id_for_commit "${MERGE_BASE_SHA}" || true)"
fi

if [[ -z "${BASELINE_PIPELINE_ID}" ]]; then
  FALLBACK_TO_MASTER="true"
  BASELINE_SOURCE="${TARGET_BRANCH}"

  BASELINE_SHA="$(git rev-parse "origin/${TARGET_BRANCH}" 2>/dev/null || true)"
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
