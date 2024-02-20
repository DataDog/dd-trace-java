#!/usr/bin/env bash

# Save all important reports and artifacts into (project-root)/results
# This folder will be saved by circleci and available after test runs.

set -e
#Enable '**' support
shopt -s globstar

TEST_RESULTS_DIR=./results
mkdir -p $TEST_RESULTS_DIR >/dev/null 2>&1

echo "saving test results"
mkdir -p $TEST_RESULTS_DIR
find workspace/**/build/test-results -name \*.xml -exec sh -c '
  file=$(echo "$0" | rev | cut -d "/" -f 1,2,5 | rev | tr "/" "_")
  cp "$0" "$1/$file"' {} $TEST_RESULTS_DIR \;
