#!/usr/bin/env bash

set -eux

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]:-$0}")" &>/dev/null && pwd 2>/dev/null)"
source "${SCRIPT_DIR}/config-benchmark-env.sh"
source "${SCRIPT_DIR}/fetch-artifacts.sh"

export SCRIPT_DIR

mkdir -p "${TRACERS_PATH}"
case "${UPSTREAM_BRANCH:-}" in
"")
  message "Running locally: using latest release for both candidate and baseline"
  fetch_agent "${CANDIDATE_AGENT_PATH}" "latest_release"
  fetch_agent "${BASELINE_AGENT_PATH}" "latest_release"
  ;;
master)
  message "Running after merge to master: comparing CI artefacts against latest release"
  fetch_agent "${CANDIDATE_AGENT_PATH}" "current-ci-pipeline"
  fetch_agent "${BASELINE_AGENT_PATH}" "latest_release"
  ;;
*)
  message "Running in a PR: comparing CI artefacts against master"
  fetch_source "${SOURCE_PATH}" "current-ci-pipeline"
  fetch_agent "${CANDIDATE_AGENT_PATH}" "current-ci-pipeline"
  fetch_agent "${BASELINE_AGENT_PATH}" "master"
  ;;
esac

if [[ ! -s "${BASELINE_AGENT_PATH}" ]]; then
  message "No agent available at ${BASELINE_AGENT_PATH}, exiting now"
  exit 1
fi
if [[ ! -s "${CANDIDATE_AGENT_PATH}" ]]; then
  message "No agent available at ${CANDIDATE_AGENT_PATH}, exiting now"
  exit 1
fi

