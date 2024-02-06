#!/usr/bin/env bash
set -euo pipefail

CIRCLE_PULL_REQUEST="${CIRCLE_PULL_REQUEST:-}"
CCI_PR_NUMBER="${CIRCLE_PR_NUMBER:-${CIRCLE_PULL_REQUEST##*/}}"

if [[ "${CIRCLE_BRANCH:-}" != "master" && -n "${CCI_PR_NUMBER:-}" ]]
then
  FETCH_REFS="${FETCH_REFS:-} +refs/pull/${CCI_PR_NUMBER}/merge:refs/pull/${CCI_PR_NUMBER}/merge +refs/pull/${CCI_PR_NUMBER}/head:refs/pull/${CCI_PR_NUMBER}/head"
  git fetch -u origin ${FETCH_REFS}
  head_ref="$(git show-ref --hash refs/pull/${CCI_PR_NUMBER}/head)"
  merge_ref="$(git show-ref --hash refs/pull/${CCI_PR_NUMBER}/merge)"
  if git merge-base --is-ancestor "$head_ref" "$merge_ref"; then
    git checkout "pull/${CCI_PR_NUMBER}/merge"
  else
    echo "[WARN] There is a merge conflict between master and PR ${CCI_PR_NUMBER}, merge branch cannot be checked out."
    git checkout "pull/${CCI_PR_NUMBER}/head"
  fi
fi
