#!/usr/bin/env bash
set -eu

if [[ -z ${CIRCLE_PULL_REQUEST:-} ]]; then
  echo "Not a pull request, skipping."
  exit 0
fi

CCI_PR_NUMBER="${CIRCLE_PR_NUMBER:-${CIRCLE_PULL_REQUEST##*/}}"

if [[ "$CIRCLE_BRANCH" != "master" && -n "${CCI_PR_NUMBER}" ]]; then
  FETCH_REFS="${FETCH_REFS} +refs/pull/${CCI_PR_NUMBER}/merge:refs/pull/${CCI_PR_NUMBER}/merge +refs/pull/${CCI_PR_NUMBER}/head:refs/pull/${CCI_PR_NUMBER}/head"
  git fetch -u origin ${FETCH_REFS}

  if git merge-base --is-ancestor $(git show-ref --hash refs/pull/${CCI_PR_NUMBER}/head) $(git show-ref --hash refs/pull/${CCI_PR_NUMBER}/merge); then
    git checkout "pull/${CCI_PR_NUMBER}/merge"
  else
    echo "[WARN] There is a merge conflict between master and PR ${CCI_PR_NUMBER}, merge branch cannot be checked out."
    git checkout "pull/${CCI_PR_NUMBER}/head"
  fi
fi
