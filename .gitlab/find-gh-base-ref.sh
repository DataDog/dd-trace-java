#!/usr/bin/env bash
# Determines the base branch for the current PR (if we are running in a PR).
set -euo pipefail

# Happy path: if we're just one commit away from master, base ref is master.
if [[ $(git log --pretty=oneline origin/master..HEAD | wc -l) -eq 1 ]]; then
  echo "We are just one commit away from master, base ref is master" >&2
  echo "master"
  exit 0
fi

# In GitLab: we have no reference to the base branch or even the PR number.
# We have to find it from the current branch name, which is defined in
# CI_COMMIT_REF_NAME.
if [[ -z "${CI_COMMIT_REF_NAME}" ]]; then
  echo "CI_COMMIT_REF_NAME is not set, not running in GitLab CI?" >&2
  exit 1
fi

if [[ -z "${GITHUB_TOKEN:-}" ]]; then
  echo "GITHUB_TOKEN is not set, fetching from AWS SSM" >&2
  if ! command -v aws >/dev/null 2>&1; then
    echo "aws is not installed, please install it" >&2
    exit 1
  fi
  GITHUB_TOKEN=$(aws ssm get-parameter --name "ci.$CI_PROJECT_NAME.gh_token" --with-decryption --query "Parameter.Value" --output text)
  if [[ -z "${GITHUB_TOKEN}" ]]; then
    echo "Failed to fetch GITHUB_TOKEN from AWS SSM" >&2
    exit 1
  fi
  export GITHUB_TOKEN
fi

if ! command -v curl >/dev/null 2>&1; then
  echo "curl is not installed, please install it" >&2
  exit 1
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "jq is not installed, please install it" >&2
  exit 1
fi

while true; do
  set +e
  PR_DATA=$(curl \
    -XGET \
    --silent \
    --include \
    --fail-with-body \
    -H 'Accept: application/vnd.github+json' \
    -H "Authorization: Bearer ${GITHUB_TOKEN}" \
    -H "X-GitHub-Api-Version: 2022-11-28" \
    "https://api.github.com/repos/datadog/dd-trace-java/pulls?head=DataDog:${CI_COMMIT_REF_NAME}&sort=updated&direction=desc")
  exit_code=$?
  set -e
  if [[ ${exit_code} -eq 0 ]]; then
    PR_NUMBER=$(echo "$PR_DATA" | sed '1,/^[[:space:]]*$/d' | jq -r '.[].number')
    PR_BASE_REF=$(echo "$PR_DATA" | sed '1,/^[[:space:]]*$/d' | jq -r '.[].base.ref')
    echo "PR is https://github.com/datadog/dd-trace-java/pull/${PR_NUMBER} and base ref is ${PR_BASE_REF}">&2
    echo "${PR_BASE_REF}"
    exit 0
  fi
  if echo "$PR_DATA" | grep -q "^x-ratelimit-reset:"; then
    reset_timestamp=$(echo -n "$PR_DATA" | grep "^x-ratelimit-reset:" | sed -e 's/^x-ratelimit-reset: //' -e 's/\r//')
    now=$(date +%s)
    sleep_time=$((reset_timestamp - now + 1))
    echo "GitHub rate limit exceeded, sleeping for ${sleep_time} seconds" >&2
    sleep "${sleep_time}"
    continue
  fi
  echo -e "GitHub request failed for an unknown reason:\n$(echo "$PR_DATA" | sed '/^$/q')" >&2
  echo "Assuming base ref is master" >&2
  echo "master"
  exit 0
done
