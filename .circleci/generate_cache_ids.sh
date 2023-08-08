#!/usr/bin/env bash
set -eu

# Everything falls back to the main cache
BASE_CACHE_ID="main"
if [ "$CIRCLE_BRANCH" == "master" ];
then
  # If we're on a the main branch, then they are the same
  echo "${BASE_CACHE_ID}" >| _circle_ci_cache_id
else
  # If we're on a PR branch, then we use the name of the branch and the
  # PR number as a stable identifier for the branch cache
  CIRCLE_PULL_REQUEST="${CIRCLE_PULL_REQUEST:-}"
  echo "${CIRCLE_BRANCH}-${CIRCLE_PULL_REQUEST##*/}" >| _circle_ci_cache_id
fi

# Have new branches start from the main cache
echo "${BASE_CACHE_ID}" >| _circle_ci_cache_base_id

