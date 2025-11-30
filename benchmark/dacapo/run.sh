#!/usr/bin/env bash
set -eu

source "${UTILS_DIR}/update-java-version.sh" 11

function message() {
  echo "$(date +"%T"): $1"
}

run_benchmark() {
  local type=$1

  message "dacapo benchmark: ${type} started"

  # export the benchmark
  export BENCHMARK="${type}"

  # create output folder for the test
  export OUTPUT_DIR="${REPORTS_DIR}/dacapo/${type}"
  mkdir -p "${OUTPUT_DIR}"

  # run the bp-runner test
  bp-runner bp-runner.yml &>"${OUTPUT_DIR}/${type}.json"

  message "dacapo benchmark: ${type} finished"
}

if [ "$#" == '2' ]; then
  run_benchmark "$2"
else
  for benchmark in biojava tomcat ; do
    run_benchmark "${benchmark}"
  done
fi

