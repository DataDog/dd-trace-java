#!/usr/bin/env bash
set -eu

LEFTOVER_FILES="$(find . -type f -regex '.*\.orig$')"
if [[ -n $LEFTOVER_FILES ]]; then
  echo -e "Found leftover files in the commit:\n$LEFTOVER_FILES"
  exit 1
else
  echo "No leftover files"
fi
