#!/usr/bin/env bash
set -eu

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

    # substitute environment variables in the json file
    benchmark=$(mktemp)
    # shellcheck disable=SC2046
    # shellcheck disable=SC2016
    envsubst "$(printf '${%s} ' $(env | cut -d'=' -f1))" <benchmark.json >"${benchmark}"

    # run the sirun test
    sirun "${benchmark}" &>"${OUTPUT_DIR}/${app}.json"

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
