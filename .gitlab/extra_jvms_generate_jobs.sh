#!/usr/bin/env bash

set -e

DOTENV_FILE="extra-jvms.env"

# Initialise with empty values so the bridge job always has the variables defined
printf 'EXTRA_TEST_JVMS=\nNON_DEFAULT_JVMS=false\n' > "$DOTENV_FILE"

if [ -z "$CI_COMMIT_BRANCH" ]; then
  echo "No branch detected - skipping extra JVM tests"
  exit 0
fi

echo "Checking extra JVM test labels for branch $CI_COMMIT_BRANCH..."

set +e
pr_number=$(gh pr list --repo DataDog/dd-trace-java --head "$CI_COMMIT_BRANCH" --state open --json number --jq '.[0].number' 2>&1)
pr_number_status=$?
set -e

if [ $pr_number_status -ne 0 ]; then
  echo "Failed to query PR (gh command failed with status $pr_number_status) - skipping"
  exit 0
fi
if [ -z "$pr_number" ]; then
  echo "No open PR found for branch $CI_COMMIT_BRANCH - skipping"
  exit 0
fi

echo "PR #${pr_number} found, checking labels..."

set +e
labels=$(gh pr view "$pr_number" --repo DataDog/dd-trace-java --json labels --jq '.labels[].name' 2>&1)
labels_status=$?
set -e

if [ $labels_status -ne 0 ]; then
  echo "Failed to query PR labels (gh command failed with status $labels_status) - skipping"
  exit 0
fi

# Check for run-tests: all to run every non-default JVM without filtering
if echo "$labels" | grep -qF 'run-tests: all'; then
  echo "PR #$pr_number has 'run-tests: all' label - will trigger all non-default JVM tests"
  printf 'EXTRA_TEST_JVMS=\nNON_DEFAULT_JVMS=true\n' > "$DOTENV_FILE"
  exit 0
fi

# Valid non-default JVM identifiers (must match testJvm values in .gitlab-ci.yml)
ALL_NON_DEFAULT_JVMS=("semeru8" "semeru11" "semeru17" "zulu8" "zulu11" "oracle8" "ibm8")

requested_jvms=()
for jvm in "${ALL_NON_DEFAULT_JVMS[@]}"; do
  if echo "$labels" | grep -qF "run-tests: $jvm"; then
    requested_jvms+=("$jvm")
    echo "Found label: run-tests: $jvm"
  fi
done

if [ ${#requested_jvms[@]} -eq 0 ]; then
  echo "PR #$pr_number has no run-tests: <jvm> labels - skipping extra JVM tests"
  exit 0
fi

# Build the EXTRA_TEST_JVMS regex matching the format used by DEFAULT_TEST_JVMS in .gitlab-ci.yml
jvm_pattern=$(IFS='|'; echo "${requested_jvms[*]}")
extra_test_jvms="/^($jvm_pattern)\$/"
echo "Will trigger extra JVM tests for: ${requested_jvms[*]}"
echo "EXTRA_TEST_JVMS: $extra_test_jvms"

printf 'EXTRA_TEST_JVMS=%s\nNON_DEFAULT_JVMS=false\n' "$extra_test_jvms" > "$DOTENV_FILE"
