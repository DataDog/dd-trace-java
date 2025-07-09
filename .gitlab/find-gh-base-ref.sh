#!/usr/bin/env bash
# Determines the base branch for the current PR (if we are running in a PR).
set -euo pipefail

CURRENT_HEAD_SHA="$(git rev-parse HEAD)"
if [[ -z "${CURRENT_HEAD_SHA:-}" ]]; then
  echo "Failed to determine current HEAD SHA" >&2
  exit 1
fi

# 'workspace' is declared in the ci pipeline cache
CACHE_PATH=workspace/find-gh-base-ref.cache
save_cache() {
  local base_ref="$1"
  local head_sha="$2"
  mkdir -p workspace
  echo "CACHED_BASE_REF=${base_ref}" > "$CACHE_PATH"
  echo "CACHED_HEAD_SHA=${head_sha}" >> "$CACHE_PATH"
}

# Get cached result (if HEAD commit matches)
if [[ -f $CACHE_PATH ]]; then
  set -a
  source "$CACHE_PATH"
  set +a
  if [[ "$CURRENT_HEAD_SHA" == "${CACHED_HEAD_SHA:-}" && -n "${CACHED_BASE_REF:-}" ]]; then
    echo "Cache hit on $CACHE_PATH" >&2
    echo "$CACHED_BASE_REF"
    exit 0
  else
    echo "Cache miss on $CACHE_PATH" >&2
  fi
fi

# Happy path: if we're just one commit away from master, base ref is master.
if [[ $(git rev-list --count origin/master..HEAD) -eq 1 ]]; then
  echo "We are just one commit away from master, base ref is master" >&2
  save_cache "master" "$CURRENT_HEAD_SHA"
  echo "master"
  exit 0
fi

get_distance_from_merge_base() {
  local candidate_base="$1"
  local merge_base_sha
  local distance
  merge_base_sha=$(git merge-base "$candidate_base" HEAD)
  distance=$(git rev-list --count "$merge_base_sha".."$CURRENT_HEAD_SHA")
  echo "Distance from $candidate_base is $distance" >&2
  echo "$distance"
}

# Find the best base ref: the master/release branch whose merge base is closest to HEAD.
# If there are multiple candidates (e.g. immediately after a release branch is created), we cannot
# disambiguate and return an error.
# NOTE: GitHub API is more robust for this task, but we hit rate limits.
BEST_CANDIDATES=(origin/master)
BEST_DISTANCE=$(get_distance_from_merge_base origin/master)
mapfile -t CANDIDATE_BASES < <(git branch -a --sort=committerdate --format='%(refname:short)' --list 'origin/release/v*' | tac)
for candidate_base in "${CANDIDATE_BASES[@]}"; do
  distance=$(get_distance_from_merge_base "$candidate_base")
  if [[ $distance -lt $BEST_DISTANCE ]]; then
    BEST_DISTANCE=$distance
    BEST_CANDIDATES=("$candidate_base")
  elif [[ $distance -eq $BEST_DISTANCE ]]; then
    BEST_CANDIDATES+=("$candidate_base")
  fi
done

if [[ ${#BEST_CANDIDATES[@]} -eq 1 ]]; then
  # Remote the origin/ prefix
  base_ref="${BEST_CANDIDATES[0]#origin/}"
  echo "Base ref is ${base_ref}" >&2
  save_cache "${base_ref}" "$CURRENT_HEAD_SHA"
  echo "${base_ref}"
  exit 0
fi

# If base ref is ambiguous, we cannot determine the correct one.
# Example: a release branch is created, and a PR is opened starting from the
# commit where the release branch was created. The distance to the merge base
# for both master and the release branch is the same. In this case, we bail
# out, and make no assumption on which is the correct base ref.
echo "Base ref is ambiguous, candidates are: ${BEST_CANDIDATES[*]}" >&2
exit 1
