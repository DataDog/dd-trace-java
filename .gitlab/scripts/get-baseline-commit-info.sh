#!/usr/bin/env bash

set -euo pipefail

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]:-$0}")" &>/dev/null && pwd 2>/dev/null)"
readonly OUT_ENV_FILE="${1:-${SCRIPT_DIR}/../reports/baseline-info.env}"
readonly FALLBACK_MARKER_FILE="${2:-${SCRIPT_DIR}/../reports/fallback_to_master.txt}"
readonly TARGET_BRANCH="${TARGET_BRANCH:-master}"

mkdir -p "$(dirname "${OUT_ENV_FILE}")"
rm -f "${FALLBACK_MARKER_FILE}"

if [[ -z "${CI_PROJECT_ID:-}" || -z "${CI_API_V4_URL:-}" ]]; then
  echo "Missing CI_PROJECT_ID/CI_API_V4_URL environment variables." >&2
  exit 1
fi

readonly PROJECT_API_URL="${CI_API_V4_URL}/projects/${CI_PROJECT_ID}"

get_pipeline_id_for_commit() {
  local commit_sha="$1"
  
  # Get JWT token with `authanywhere`, needed for internal API authentication
  # https://datadoghq.atlassian.net/wiki/spaces/DEVX/pages/3733193227/Authanywhere
  local auth_header
  if ! auth_header="$(authanywhere --audience sdm 2>&1)"; then
    echo "ERROR: Failed to get authanywhere token: ${auth_header}" >&2
    return 1
  fi

  # Get short-lived PAT from BTI CI API
  # https://datadoghq.atlassian.net/wiki/spaces/DEVX/pages/5421924354/BTI+CI+API+-+the+swiss+army+knife+of+CI+interactions#Get-Short-lived-Project-Access-Token
  local bti_response http_status response_body private_token
  bti_response="$(curl -w "\nHTTP_STATUS:%{http_code}" --silent --show-error \
    --header "${auth_header}" \
    "https://bti-ci-api.us1.ddbuild.io/internal/ci/gitlab/token?owner=DataDog&repository=apm-reliability/dd-trace-java" 2>&1)"
  http_status="$(echo "${bti_response}" | grep "HTTP_STATUS:" | sed 's/HTTP_STATUS://')"
  response_body="$(echo "${bti_response}" | sed '/HTTP_STATUS:/d')"
  if [[ "${http_status}" != "200" ]]; then
    echo "ERROR: BTI CI API returned HTTP ${http_status} with response: ${response_body:0:200}" >&2
    return 1
  fi
  private_token="$(echo "${response_body}" | grep -o '"token":"[^"]*"' | sed 's/"token":"\([^"]*\)"/\1/')"
  if [[ -z "${private_token}" ]]; then
    echo "ERROR: Failed to retrieve private token from BTI CI API" >&2
    return 1
  fi

  # Query GitLab API to get pipeline ID that matches the commit SHA
  local api_url="${PROJECT_API_URL}/repository/commits/${commit_sha}"
  local response pipeline_id
  response="$(curl --request GET --silent --header "PRIVATE-TOKEN: ${private_token}" --show-error "${api_url}" 2>&1)"
  pipeline_id="$(echo "${response}" | grep -o '"last_pipeline"[^}]*"id":[0-9]*' | grep -o '[0-9]*$' | head -1 || true)"
  if [[ -n "${pipeline_id}" && "${pipeline_id}" != "null" ]]; then
    echo "Found pipeline ID: ${pipeline_id} for commit: ${commit_sha}" >&2
    echo "${pipeline_id}"
    return 0
  else
    echo "Could not find pipeline ID for commit ${commit_sha}" >&2
    return 1
  fi
}

get_branch_head_sha() {
  local branch="$1"
  
  # Get JWT token with `authanywhere`, needed for internal API authentication
  local auth_header
  if ! auth_header="$(authanywhere --audience sdm 2>&1)"; then
    echo "ERROR: Failed to get authanywhere token: ${auth_header}" >&2
    return 1
  fi

  # Get short-lived PAT from BTI CI API
  local bti_response http_status response_body private_token
  bti_response="$(curl -w "\nHTTP_STATUS:%{http_code}" --silent --show-error \
    --header "${auth_header}" \
    "https://bti-ci-api.us1.ddbuild.io/internal/ci/gitlab/token?owner=DataDog&repository=apm-reliability/dd-trace-java" 2>&1)"
  http_status="$(echo "${bti_response}" | grep "HTTP_STATUS:" | sed 's/HTTP_STATUS://')"
  response_body="$(echo "${bti_response}" | sed '/HTTP_STATUS:/d')"
  
  if [[ "${http_status}" != "200" ]]; then
    echo "ERROR: BTI CI API returned HTTP ${http_status} with response: ${response_body:0:200}" >&2
    return 1
  fi
  
  private_token="$(echo "${response_body}" | grep -o '"token":"[^"]*"' | sed 's/"token":"\([^"]*\)"/\1/')"
  if [[ -z "${private_token}" ]]; then
    echo "ERROR: Failed to retrieve private token from BTI CI API" >&2
    return 1
  fi

  # Query GitLab API to get branch head SHA
  local api_url="${PROJECT_API_URL}/repository/branches/${branch}"
  local response branch_sha
  response="$(curl --request GET --silent --header "PRIVATE-TOKEN: ${private_token}" --show-error "${api_url}" 2>&1)"
  branch_sha="$(echo "${response}" | grep -o '"commit"[^}]*"id":"[a-f0-9]\{40\}"' | sed -E 's/.*"id":"([a-f0-9]{40})".*/\1/' | head -1 || true)"

  if [[ -n "${branch_sha}" ]]; then
    echo "${branch_sha}"
    return 0
  else
    echo "ERROR: Could not find branch head SHA for branch ${branch}" >&2
    return 1
  fi
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
  if [[ -z "${BASELINE_SHA}" ]]; then
    BASELINE_SHA="$(get_branch_head_sha "${TARGET_BRANCH}")"
  fi
  BASELINE_PIPELINE_ID="$(get_pipeline_id_for_commit "${BASELINE_SHA}" || true)"
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
