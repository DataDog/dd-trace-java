#!/usr/bin/env bash

set -e
#Enable '**' support
shopt -s globstar

REPORTS_DIR=./reports
mkdir -p $REPORTS_DIR >/dev/null 2>&1

echo "saving muzzle dependency reports"

find workspace/**/build/muzzle-deps-results -type f -name 'dd-java-agent_instrumentation.csv' -exec cp {} $REPORTS_DIR/ \;
