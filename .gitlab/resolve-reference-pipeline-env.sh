#!/usr/bin/env bash

if [ "$(git rev-parse --is-shallow-repository)" = "true" ]; then
  git fetch --quiet --no-recurse-submodules --unshallow origin master || exit 1
else
  git fetch --quiet --no-recurse-submodules origin master || exit 1
fi

if ! REFERENCE_SHA="$(git merge-base FETCH_HEAD "${CI_COMMIT_SHA}")"; then
  echo "Unable to resolve branch fork point between master and ${CI_COMMIT_SHA}"
  exit 1
fi
export REFERENCE_SHA
if [ -z "$REFERENCE_SHA" ]; then
  echo "Unable to resolve branch fork point between master and ${CI_COMMIT_SHA}"
  exit 1
fi

if [ -z "${GITLAB_TOKEN:-}" ]; then
  echo "Unable to resolve fork-point master build job: GITLAB_TOKEN with read_api access is required."
  echo "CI_JOB_TOKEN can download artifacts, but it cannot read commit statuses to discover the historical build job."
  exit 1
fi
gitlab_headers=(--header "Accept: application/json" --header "PRIVATE-TOKEN: ${GITLAB_TOKEN}")

# Missing piece: this API lookup needs read_api access. CI_JOB_TOKEN can download
# the resolved job artifacts, but it cannot discover the historical build job.
if ! reference_statuses="$(
  curl --fail --silent --show-error "${gitlab_headers[@]}" \
    "${CI_API_V4_URL}/projects/${CI_PROJECT_ID}/repository/commits/${REFERENCE_SHA}/statuses?per_page=100"
)"; then
  echo "Unable to read GitLab commit statuses for fork point ${REFERENCE_SHA}"
  echo "This lookup needs GitLab API access to commit statuses."
  echo "Provide GITLAB_TOKEN with read_api access or resolve this metadata in a job image that can mint an API token."
  exit 1
fi

REFERENCE_BUILD_JOB_ID="$(
  printf '%s\n' "$reference_statuses" \
    | jq -r '[.[] | select(.name == "build" and .ref == "master" and .status == "success")] | sort_by(.id) | last | .id // empty'
)"
export REFERENCE_BUILD_JOB_ID
REFERENCE_PIPELINE_ID="$(
  printf '%s\n' "$reference_statuses" \
    | jq -r '[.[] | select(.name == "build" and .ref == "master" and .status == "success")] | sort_by(.id) | last | .pipeline_id // empty'
)"
export REFERENCE_PIPELINE_ID
if [ -z "$REFERENCE_BUILD_JOB_ID" ]; then
  echo "Unable to find a successful master build job for fork point ${REFERENCE_SHA}"
  exit 1
fi

REFERENCE_BUILD_JOB_URL="${CI_PROJECT_URL}/-/jobs/${REFERENCE_BUILD_JOB_ID}"
export REFERENCE_BUILD_JOB_URL
REFERENCE_PIPELINE_URL="${CI_PROJECT_URL}/-/pipelines/${REFERENCE_PIPELINE_ID}"
export REFERENCE_PIPELINE_URL
REFERENCE_BUILD_JOB_ARTIFACTS_URL="${CI_API_V4_URL}/projects/${CI_PROJECT_ID}/jobs/${REFERENCE_BUILD_JOB_ID}/artifacts"
export REFERENCE_BUILD_JOB_ARTIFACTS_URL

echo "Using fork-point master build job ${REFERENCE_BUILD_JOB_ID} (${REFERENCE_BUILD_JOB_URL}) from pipeline ${REFERENCE_PIPELINE_ID} for ${REFERENCE_SHA}"
