#!/usr/bin/env bash
set -eu

if [[ -z ${CIRCLE_PULL_REQUEST:-} ]]; then
  echo "Not a pull request, not skipping."
  exit 0
fi

PATTERN="${1}"

# We know that we have checked out the PR merge branch (unless there are conflicts),
# so the HEAD commit should be a merge. As a backup, if anything goes wrong with the diff,
# we'll run everything.

DIFF_SPEC="$(git show HEAD | grep -e "^Merge:" | cut -d ' ' -f 2- | sed 's/ /.../')"
if [[ -z ${DIFF_SPEC} ]]; then
  echo "Cannot diff changes, not skipping."
  exit 0
fi

CHANGED_FILES="$(echo "${DIFF_SPEC}" | xargs git diff --name-only | sed -e 's~ ~$~g')"
MATCHED_FILES="$(echo "${CHANGED_FILES}" | grep -E "${PATTERN}" || true)"
if [[ -n ${MATCHED_FILES} ]]; then
  echo "Relevent changes:"
  echo "${MATCHED_FILES}"
else
  echo "No relevant changes, skipping."
  circleci step halt
fi
