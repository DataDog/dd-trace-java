#!/usr/bin/env bash

set -e
#Enable '**' support
shopt -s globstar

REPORTS_DIR=./reports
mkdir -p $REPORTS_DIR >/dev/null 2>&1

echo "saving muzzle dependency reports"

find workspace/**/build/muzzle-deps-results -type f -name '*.csv' -exec sed -e '$s/$/\n/' {} \; | head -n -1 | sort | uniq > $REPORTS_DIR/muzzle.csv
