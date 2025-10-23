#!/usr/bin/env bash

set -e

add_dummy_job() {
  cat <<EOF >>ci-visibility-test-environment.yml
skip-ci-visibility-test-environment:
  stage: ci-visibility-tests
  tags: [ "arch:amd64" ]
  script:
    - echo "PR does not have required label - CI Visibility test environment not triggered"
EOF
}

# Generate yml
cat <<EOF >ci-visibility-test-environment.yml
stages:
  - ci-visibility-tests

EOF

if [ -z "$CI_COMMIT_BRANCH" ]; then
  echo "No branch detected - skipping trigger"
  add_dummy_job
  exit 0
fi

echo "Performing trigger checks for ci-visibility test-environment..."
set +e
pr_number=$(gh pr list --repo DataDog/dd-trace-java --head "$CI_COMMIT_BRANCH" --state open --json number --jq '.[0].number' 2>&1)
pr_number_status=$?
set -e

if [ $pr_number_status -ne 0 ]; then
  echo "Failed to query PR (gh command failed with status $pr_number_status ) - skipping trigger"
  add_dummy_job
  exit 0
fi
if [ -z "$pr_number" ]; then
  echo "No open PR found for branch $CI_COMMIT_BRANCH - skipping trigger"
  add_dummy_job
  exit 0
fi

echo "PR #${pr_number} found, checking labels..."
set +e
labels=$(gh pr view "$pr_number" --repo DataDog/dd-trace-java --json labels --jq '.labels[].name' 2>&1)
labels_status=$?
set -e

if [ $labels_status -ne 0 ]; then
  echo "Failed to query PR labels (gh command failed with status $labels_status) - skipping trigger"
  add_dummy_job
  exit 0
fi
if [ -z "$labels" ] || ! echo "$labels" | grep -q "comp: ci visibility"; then
  echo "PR #$pr_number is not a CI Visibility PR - skipping trigger"
  add_dummy_job
  exit 0
fi

echo "PR #$pr_number is a CI Visibility PR - triggering test environment"

cat <<EOF >>ci-visibility-test-environment.yml
run-ci-visibility-test-environment:
  stage: ci-visibility-tests
  trigger:
    project: DataDog/apm-reliability/test-environment
    branch: main
    strategy: depend
  variables:
    UPSTREAM_PACKAGE_JOB: build
    UPSTREAM_PROJECT_ID: "$CI_PROJECT_ID"
    UPSTREAM_PROJECT_NAME: "$CI_PROJECT_NAME"
    UPSTREAM_PIPELINE_ID: "$CI_PIPELINE_ID"
    UPSTREAM_BRANCH: "$CI_COMMIT_BRANCH"
    UPSTREAM_TAG: "$CI_COMMIT_TAG"
    UPSTREAM_COMMIT_AUTHOR: "$CI_COMMIT_AUTHOR"
    UPSTREAM_COMMIT_SHORT_SHA: "$CI_COMMIT_SHORT_SHA"
    TRACER_LANG: java
    JAVA_TRACER_REF_TO_TEST: "$CI_COMMIT_BRANCH"
    JAVA_TRACER_PR_TO_TEST: "$pr_number"
EOF
