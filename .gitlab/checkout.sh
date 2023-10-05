#!/usr/bin/env bash

set -eu

function message() {
  echo "$(date +"%T"): $1"
}

readonly branch_name="${CI_COMMIT_REF_NAME:-}"
readonly commit_hash="${CI_COMMIT_SHA:-}"

function get_branch_distance()
{
  local current_position=$1
  local target_branch=$2
  local merge_base
  merge_base=$(git merge-base "${current_position}" "${target_branch}")
  if [[ $? -eq 0 ]]; then
    local distance
    distance=$(git rev-list --count "${merge_base}..${current_position}")
    if [[ $? -eq 0 ]]; then
      echo "${distance} ${target_branch}"
    fi
  fi
}

function is_valid()
{
  local object=$1
  $(git log --oneline -n 1 "${object}" 1>/dev/null 2>/dev/null)
  if [[ $? -eq 0 ]]; then
    echo "valid"
  fi
}

function back_out_and_exit() {
    local base_branch=$1
    message "Failed to create merge commit for branch ${branch_name} with branch ${base_branch}. Will build provided commit ${commit_hash}"
    git checkout "$commit_hash"
    exit 0
}

# Do we have a sane commit hash?
if [[ "${commit_hash}" = "" || $(is_valid ${commit_hash}) = ""  ]]; then
  message "Unrecognized commit hash '${commit_hash}'. Will not try to create merge commit."
  exit 0
fi

# Are we on a branch that should try to build a merge commit?
case "${branch_name}" in
  master | main | release/v* | "")
    message "Will build provided commit ${commit_hash} for branch '${branch_name}'."
    exit 0
    ;;
  *)
    if [[ $(is_valid "${branch_name}") = "" && $(is_valid "origin/${branch_name}") = "" ]]; then
      message "Can't find branch ${branch_name}. Will build provided commit ${commit_hash}."
      exit 0
    fi
    message "Will try to create a merge commit for branch ${branch_name}."
    ;;
esac

# List master/main and all release and project branches
target_branches=$(git branch -r | grep -v HEAD | grep -e '/master$' -e '/main$' -e '/release/v' -e '/project' | cut -c 3-)

if [[ "${target_branches}" = "" ]]; then
  message "Unable to find any target branches for ${branch_name}. Will build provided commit ${commit_hash}."
  exit 0
fi

# Compute the distance from the provided commit to the different branches
distances=""
while IFS= read -r branch; do
    distance=$(get_branch_distance ${commit_hash} ${branch})
    if [ "$distance" != "" ]; then
      if [ "$distances" = "" ]; then
        distances="$distance"
      else
        distances="$distances"$'\n'"$distance"
      fi
    fi
done <<< "$target_branches"

# Sort the branches by shortest distance
sorted=$(echo -n "$distances" | sort -n)

# Pick the closest branch
closest=$(echo -n "$sorted" | head -n 1 | cut -d ' ' -f 2-)
if [[ "${closest}" = "" ]]; then
  message "Could not find base branch for branch ${branch_name}. Will build provided commit ${commit_hash}."
  exit 0
fi
message "Found base branch ${closest}."

exit_code=0
# Check out the closest branch
git checkout "${closest}" || exit_code=$?
if [[ $exit_code -ne 0 ]]; then
  back_out_and_exit "${closest}"
fi

# Create a merge branch
git checkout -b "merge_${commit_hash}_${RANDOM}" || exit_code=$?
if [[ $exit_code -ne 0 ]]; then
  back_out_and_exit "${closest}"
fi

# Merge the provided commit into the merge branch
git merge --commit --no-edit --no-verify "${commit_hash}" || exit_code=$?
if [[ $exit_code -ne 0 ]]; then
  git merge --abort || true
  back_out_and_exit "${closest}"
fi

message "Created a merge for provided commit ${commit_hash} from branch ${branch_name} with branch ${closest}."
