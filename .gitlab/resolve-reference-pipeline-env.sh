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

reference_remote="${CI_REPOSITORY_URL:-origin}"
if ! REFERENCE_PIPELINE_ID="$(
  git ls-remote "$reference_remote" 'refs/pipelines/*' \
    | awk -v sha="$REFERENCE_SHA" '
      $1 == sha && $2 ~ /^refs\/pipelines\/[0-9]+$/ {
        sub(/^refs\/pipelines\//, "", $2)
        print $2
      }
    ' \
    | sort -n \
    | tail -n 1
)"; then
  echo "Unable to resolve master pipeline id for ${REFERENCE_SHA}"
  exit 1
fi
export REFERENCE_PIPELINE_ID
REFERENCE_PIPELINE_URL="${CI_PROJECT_URL}/-/pipelines/${REFERENCE_PIPELINE_ID}"
export REFERENCE_PIPELINE_URL
if [ -z "$REFERENCE_PIPELINE_ID" ]; then
  echo "Unable to find a successful master pipeline for ${REFERENCE_SHA}"
  exit 1
fi

echo "Using master pipeline ${REFERENCE_PIPELINE_ID} (${REFERENCE_PIPELINE_URL}) for ${REFERENCE_SHA}"
