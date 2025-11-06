#!/usr/bin/env bash

set -e

function message() {
  echo "$(date +"%T"): $1"
}

function healthcheck() {
  local url=$1

  while true; do
    if [[ $(curl -fso /dev/null -w "%{http_code}" "${url}") = 200 ]]; then
      break
    fi
  done
}

type=$1

if [ -n "$CI_JOB_TOKEN" ]; then
  # Inside BP, so we can assume 24 CPU cores on the second socket available and set CPU affinity
  export CPU_AFFINITY_K6="taskset -c 24-27 "
else
  export CPU_AFFINITY_K6=""
fi

source "${UTILS_DIR}/update-java-version.sh" 17

run_app_benchmark() {
  local app=$1
  
  message "${type} benchmark: ${app} started"

  export OUTPUT_DIR="${REPORTS_DIR}/${type}/${app}"
  mkdir -p ${OUTPUT_DIR}

  export LOGS_DIR="${ARTIFACTS_DIR}/${type}/${app}"
  mkdir -p ${LOGS_DIR}

  # Using profiler variants for healthcheck as they are the slowest
  if [ "${app}" == "petclinic" ]; then
    HEALTHCHECK_URL=http://localhost:8082
    REPETITIONS_COUNT=5
    PORT_START=8080
    PORT_END=8085
    HEALTHCHECK_PATH=""
  elif [ "${app}" == "insecure-bank" ]; then
    HEALTHCHECK_URL=http://localhost:8088/login
    REPETITIONS_COUNT=5
    PORT_START=8086
    PORT_END=8091
    HEALTHCHECK_PATH="/login"
  else
    echo "Unknown app ${app}"
    exit 1
  fi

  for i in $(seq 1 $REPETITIONS_COUNT); do
    bash -c "${UTILS_DIR}/../${type}/${app}/start-servers.sh" &

    echo "Waiting for serves to start..."
    for port in $(seq $PORT_START $PORT_END); do
      healthcheck http://localhost:$port$HEALTHCHECK_PATH
    done
    echo "Servers are up!"

    (
      cd ${app} &&
      bash -c "${CPU_AFFINITY_K6}${UTILS_DIR}/run-k6-load-test.sh 'pkill java'"
    )
  done

  message "${type} benchmark: ${app} finished"
}

# Run petclinic and insecure-bank benchmarks in parallel to reduce total runtime
for app in *; do
  if [[ ! -d "${app}" ]]; then
    continue
  fi
  run_app_benchmark "${app}" &
done

# Wait for all background jobs to complete
wait
