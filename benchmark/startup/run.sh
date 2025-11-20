#!/usr/bin/env bash
set -eu

source "${UTILS_DIR}/update-java-version.sh" 17

# from run-sirun-benchmarks.sh, matches dacapo and load benchmark run.sh scripts
function message() {
  echo "$(date +"%T"): $1"
}

run_benchmark() {
  local type=$1
  local app=$2
  if [[ -d "${app}" ]] && [[ -f "${app}/benchmark.json" ]]; then

    message "${type} benchmark: ${app} started"
    cd "${app}"

    # create output folder for the test
    export OUTPUT_DIR="${REPORTS_DIR}/${type}/${app}"
    mkdir -p "${OUTPUT_DIR}"

    # run the bp-runner test
    bp-runner bp-runner.yml &>"${OUTPUT_DIR}/${app}.json"

    message "${type} benchmark: ${app} finished"

    cd ..
  fi
}

if [ "$#" == '2' ]; then
  run_benchmark "$@"
else
  for folder in *; do
    run_benchmark "$1" "${folder}"
  done
fi
