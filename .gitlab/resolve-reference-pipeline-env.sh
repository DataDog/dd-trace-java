#!/usr/bin/env bash

if [ "$(git rev-parse --is-shallow-repository)" = "true" ]; then
  git fetch --quiet --unshallow origin master || exit 1
else
  git fetch --quiet origin master || exit 1
fi

if ! REFERENCE_SHA="$(git rev-parse FETCH_HEAD)"; then
  echo "Unable to resolve master revision"
  exit 1
fi
export REFERENCE_SHA
if [ -z "$REFERENCE_SHA" ]; then
  echo "Unable to resolve master revision"
  exit 1
fi

REFERENCE_BUILD_JOB_URL="${CI_PROJECT_URL}/-/jobs/artifacts/master/browse?job=build"
export REFERENCE_BUILD_JOB_URL
REFERENCE_BUILD_JOB_ID="latest-successful-master-build"
export REFERENCE_BUILD_JOB_ID
REFERENCE_BUILD_JOB_ARTIFACTS_URL="${CI_API_V4_URL}/projects/${CI_PROJECT_ID}/jobs/artifacts/master/download?job=build"
export REFERENCE_BUILD_JOB_ARTIFACTS_URL

echo "Using latest successful master build artifacts (${REFERENCE_BUILD_JOB_URL}) for ${REFERENCE_SHA}"
