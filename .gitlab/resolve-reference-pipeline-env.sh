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

if ! reference_commit="$(
  curl --fail --silent --show-error --header "JOB-TOKEN: ${CI_JOB_TOKEN}" \
    "${CI_API_V4_URL}/projects/${CI_PROJECT_ID}/repository/commits/${REFERENCE_SHA}"
)"; then
  echo "Unable to read commit metadata for ${REFERENCE_SHA}"
  exit 1
fi
if ! REFERENCE_PIPELINE_ID="$(
  printf '%s\n' "$reference_commit" \
    | jq -r --arg sha "$REFERENCE_SHA" '.last_pipeline | select(.sha == $sha and .ref == "master" and .status == "success") | .id // empty'
)"; then
  echo "Unable to parse master pipeline id for ${REFERENCE_SHA}"
  exit 1
fi
export REFERENCE_PIPELINE_ID
if ! REFERENCE_PIPELINE_URL="$(
  printf '%s\n' "$reference_commit" \
    | jq -r --arg sha "$REFERENCE_SHA" '.last_pipeline | select(.sha == $sha and .ref == "master" and .status == "success") | .web_url // empty'
)"; then
  echo "Unable to parse master pipeline URL for ${REFERENCE_SHA}"
  exit 1
fi
export REFERENCE_PIPELINE_URL
if [ -z "$REFERENCE_PIPELINE_ID" ]; then
  echo "Unable to find a successful master pipeline for ${REFERENCE_SHA}"
  exit 1
fi

echo "Using master pipeline ${REFERENCE_PIPELINE_ID} (${REFERENCE_PIPELINE_URL}) for ${REFERENCE_SHA}"
