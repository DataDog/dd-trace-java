#!/usr/bin/env bash

if [ "$(git rev-parse --is-shallow-repository)" = "true" ]; then
  git fetch --quiet --unshallow origin master || exit 1
else
  git fetch --quiet origin master || exit 1
fi

if ! REFERENCE_SHA="$(git merge-base FETCH_HEAD "${CI_COMMIT_SHA}")"; then
  echo "Unable to resolve merge base between master and ${CI_COMMIT_SHA}"
  exit 1
fi
export REFERENCE_SHA
if [ -z "$REFERENCE_SHA" ]; then
  echo "Unable to resolve merge base between master and ${CI_COMMIT_SHA}"
  exit 1
fi

if [ -z "${GITHUB_TOKEN:-}" ] && [ -n "${DDOCTOSTS_ID_TOKEN:-}" ]; then
  if ! command -v dd-octo-sts >/dev/null 2>&1; then
    echo "DDOCTOSTS_ID_TOKEN is set, but dd-octo-sts is not available"
    exit 1
  fi

  github_token_file="$(mktemp)"
  dd-octo-sts debug --scope DataDog/dd-trace-java --policy self.gitlab.github-access.read
  dd-octo-sts token --scope DataDog/dd-trace-java --policy self.gitlab.github-access.read > "$github_token_file"
  export GITHUB_TOKEN="$(cat "$github_token_file")"
  trap 'dd-octo-sts revoke -t "$GITHUB_TOKEN" || true; rm -f "$github_token_file"' EXIT
fi

github_headers=(--header "Accept: application/vnd.github+json")
if [ -n "${GITHUB_TOKEN:-}" ]; then
  github_headers+=(--header "Authorization: Bearer ${GITHUB_TOKEN}")
fi

if ! reference_status="$(
  curl --fail --silent --show-error "${github_headers[@]}" \
    "https://api.github.com/repos/DataDog/dd-trace-java/commits/${REFERENCE_SHA}/status"
)"; then
  echo "Unable to read GitHub commit status for ${REFERENCE_SHA}"
  exit 1
fi

REFERENCE_BUILD_JOB_URL="$(
  printf '%s\n' "$reference_status" \
    | jq -r '.statuses[] | select(.context == "dd-gitlab/build" and .state == "success") | .target_url // empty' \
    | head -n 1
)"
export REFERENCE_BUILD_JOB_URL
REFERENCE_BUILD_JOB_ID="$(
  printf '%s\n' "$REFERENCE_BUILD_JOB_URL" \
    | sed -n 's#.*/builds/\([0-9][0-9]*\).*#\1#p'
)"
export REFERENCE_BUILD_JOB_ID
REFERENCE_BUILD_JOB_ARTIFACTS_URL="${CI_API_V4_URL}/projects/${CI_PROJECT_ID}/jobs/${REFERENCE_BUILD_JOB_ID}/artifacts"
export REFERENCE_BUILD_JOB_ARTIFACTS_URL
if [ -z "$REFERENCE_BUILD_JOB_ID" ]; then
  echo "Unable to find a successful master build job for ${REFERENCE_SHA}"
  exit 1
fi

echo "Using master build job ${REFERENCE_BUILD_JOB_ID} (${REFERENCE_BUILD_JOB_URL}) for ${REFERENCE_SHA}"
