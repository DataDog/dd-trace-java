#!/usr/bin/env bash
set -eu

if [[ -n ${CIRCLE_PR_NUMBER:-} ]]; then
  echo "Nothing to do for forked PRs, so marking this step successful"
  circleci step halt
fi
