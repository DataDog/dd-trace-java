#!/usr/bin/env bash

function get_pr_number() {
  local branch=$1

  if [ -z "$branch" ]; then
    echo "Error: Branch name is required" >&2
    return 1
  fi

  local pr_number
  pr_number=$(gh pr list --repo DataDog/dd-trace-java --head "$branch" --state open --json number --jq '.[0].number')

  if [ -z "$pr_number" ]; then
    echo "Error: No open PR found for branch $branch" >&2
    return 1
  fi

  echo "$pr_number"
  return 0
}

function get_pr_labels() {
  local pr_number=$1

  if [ -z "$pr_number" ]; then
    echo "Error: PR number is required" >&2
    return 1
  fi

  local labels
  labels=$(gh pr view "$pr_number" --repo DataDog/dd-trace-java --json labels --jq '.labels[].name')

  if [ -z "$labels" ]; then
    echo "Warning: No labels found for PR #$pr_number" >&2
    return 1
  fi

  echo "$labels"
  return 0
}

function pr_has_label() {
  local pr_number=$1
  local target_label=$2

  if [ -z "$pr_number" ] || [ -z "$target_label" ]; then
    echo "Error: PR number and label are required" >&2
    return 1
  fi

  local labels
  if ! labels=$(get_pr_labels "$pr_number"); then
    return 1
  fi

  if echo "$labels" | grep -q "$target_label"; then
    return 0
  else
    return 1
  fi
}
