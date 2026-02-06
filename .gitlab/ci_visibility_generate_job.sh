#!/usr/bin/env bash

set -e

add_dummy_job() {
  cat <<EOF >>ci-visibility-test-environment.yml
skip-ci-visibility-tests:
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

# Check if matching branch exists in test-environment
set +e
target_branch="main"
echo "DEBUG: Looking for branch '$CI_COMMIT_BRANCH' in test-environment"
echo "DEBUG: Listing all branches in test-environment:"
gh api "repos/DataDog/test-environment/branches" --jq '.[].name' 2>&1
echo "DEBUG: Attempting to fetch branch '$CI_COMMIT_BRANCH':"
branch_check=$(gh api "repos/DataDog/test-environment/branches/$CI_COMMIT_BRANCH" 2>&1)
branch_check_status=$?
echo "DEBUG: API response (status=$branch_check_status): $branch_check"
if [ $branch_check_status -eq 0 ]; then
  echo "Found matching branch '$CI_COMMIT_BRANCH' in test-environment - using it"
  target_branch="$CI_COMMIT_BRANCH"
else
  echo "No matching branch in test-environment - defaulting to 'main'"
fi
set -e

cat <<EOF >>ci-visibility-test-environment.yml
ci-visibility-test-environment:
  stage: ci-visibility-tests
  trigger:
    project: DataDog/apm-reliability/test-environment
    branch: $target_branch
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
