#!/usr/bin/env bash
set -eu
git diff --name-only "$1" | grep --invert-match --quiet -E '^(.gitlab-ci.yml|.gitlab)'
