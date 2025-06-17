#!/usr/bin/env bash
set -eu

readonly SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &>/dev/null && pwd)
export TRACER_DIR="${SCRIPT_DIR}/.."
export REPORTS_DIR="${ARTIFACTS_DIR}"
export UTILS_DIR="${SCRIPT_DIR}/utils"
export SHELL_UTILS_DIR="${UTILS_DIR}/shell"
export K6_UTILS_DIR="${UTILS_DIR}/k6"
export TRACER="${SCRIPT_DIR}/tracer/dd-java-agent.jar"
export NO_AGENT_VARIANT="no_agent"

run_benchmarks() {
  local type=$1
  if [[ -d "${type}" ]] && [[ -f "${type}/run.sh" ]]; then
    cd "${type}"
    ./run.sh "$@"
    cd "${SCRIPT_DIR}"
  fi
}

# Find or rebuild tracer to be used in the benchmarks
if [[ ! -f "${TRACER}" ]]; then
  mkdir -p "${SCRIPT_DIR}/tracer"
  cd "${TRACER_DIR}"
  readonly TRACER_VERSION=$(./gradlew properties -q | grep "version:" | awk '{print $2}')
  readonly TRACER_COMPILED="${SCRIPT_DIR}/../dd-java-agent/build/libs/dd-java-agent-${TRACER_VERSION}.jar"
  if [[ ! -f "${TRACER_COMPILED}" ]]; then
    echo "Tracer not found, starting gradle compile ..."
    ./gradlew assemble
  fi
  cp "${TRACER_COMPILED}" "${TRACER}"
  cd "${SCRIPT_DIR}"
fi

# Cleanup previous reports
rm -rf "${REPORTS_DIR}"
mkdir -p "${REPORTS_DIR}"

if [[ "$#" == '0' ]]; then
  for type in 'startup' 'load' 'dacapo'; do
    run_benchmarks "$type"
  done
else
  run_benchmarks "$@"
fi
