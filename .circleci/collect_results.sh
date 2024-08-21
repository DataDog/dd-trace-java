#!/usr/bin/env bash

# Save all important reports and artifacts into (project-root)/results
# This folder will be saved by circleci and available after test runs.

set -e
#Enable '**' support
shopt -s globstar

TEST_RESULTS_DIR=./results
mkdir -p $TEST_RESULTS_DIR >/dev/null 2>&1

mkdir -p $TEST_RESULTS_DIR

mkdir -p workspace
mapfile -t test_result_dirs < <(find workspace -name test-results -type d)

if [[ ${#test_result_dirs[@]} -eq 0 ]]; then
  echo "No test results found"
  exit 0
fi

echo "saving test results"
find "${test_result_dirs[@]}" -name \*.xml -exec sh -c '
  file=$(echo "$0" | rev | cut -d "/" -f 1,2,5 | rev | tr "/" "_")
  cp "$0" "$1/$file"' {} $TEST_RESULTS_DIR \;
