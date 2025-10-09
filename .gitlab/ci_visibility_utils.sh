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

function write_pr_comment() {
  local pr_number=$1
  local message=$2
  local header=$3

  if [ -z "$pr_number" ]; then
    echo "Error: PR number is required" >&2
    return 1
  fi

  if [ -z "$message" ]; then
    echo "Error: Message is required" >&2
    return 1
  fi

  if [ -z "$header" ]; then
    header="CI Notification"
  fi

  # Create JSON payload
  local json_payload
  json_payload=$(jq -n \
    --argjson pr_num "$pr_number" \
    --arg message "$message" \
    --arg header "$header" \
    --arg org "DataDog" \
    --arg repo "dd-trace-java" \
    '{pr_num: $pr_num, message: $message, header: $header, org: $org, repo: $repo}')

  # Ensure authanywhere is available
  if [ ! -x "./authanywhere-linux-amd64" ]; then
    echo "Error: authanywhere-linux-amd64 not found or not executable" >&2
    return 1
  fi

  # Post comment to PR
  echo "Posting comment to PR #${pr_number}"
  curl -s 'https://pr-commenter.us1.ddbuild.io/internal/cit/pr-comment' \
    -H "$(./authanywhere-linux-amd64)" \
    -H "Content-Type: application/json" \
    -X PATCH \
    -d "$json_payload"

  return $?
}

function get_downstream_pipeline_id() {
  local downstream_id
  downstream_id=$(curl --header "PRIVATE-TOKEN: ${CI_JOB_TOKEN}" --url "${CI_API_V4_URL}/projects/${CI_PROJECT_ID}/pipelines/${CI_PIPELINE_ID}/bridges")
  echo "$downstream_id"
  return 0
}
