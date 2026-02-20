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

# Optional second argument to run a specific app for load benchmarks (i.e. "petclinic" or "insecure-bank")
app_filter=${2:-""}

for app in *; do
  if [[ ! -d "${app}" ]]; then
    continue
  fi

  # Skip if app filter is specified and doesn't match
  if [[ -n "${app_filter}" ]] && [[ "${app}" != "${app_filter}" ]]; then
    continue
  fi

  message "${type} benchmark: ${app} started"

  export OUTPUT_DIR="${REPORTS_DIR}/${type}/${app}"
  mkdir -p ${OUTPUT_DIR}

  export LOGS_DIR="${ARTIFACTS_DIR}/${type}/${app}"
  mkdir -p ${LOGS_DIR}

  # Using profiler variants for healthcheck as they are the slowest
  if [ "${app}" == "petclinic" ]; then
    HEALTHCHECK_URL=http://localhost:8082
    REPETITIONS_COUNT=5
  elif [ "${app}" == "insecure-bank" ]; then
    HEALTHCHECK_URL=http://localhost:8082/login
    REPETITIONS_COUNT=5
  else
    echo "Unknown app ${app}"
    exit 1
  fi

  for i in $(seq 1 $REPETITIONS_COUNT); do
    bash -c "${UTILS_DIR}/../${type}/${app}/start-servers.sh" &

    echo "Waiting for serves to start..."
    if [ "${app}" == "petclinic" ]; then
      for port in $(seq 8080 8085); do
        healthcheck http://localhost:$port
      done
    elif [ "${app}" == "insecure-bank" ]; then
      for port in $(seq 8080 8085); do
        healthcheck http://localhost:$port/login
      done
    fi
    echo "Servers are up!"

    (
      cd ${app} &&
      bash -c "${CPU_AFFINITY_K6}${UTILS_DIR}/run-k6-load-test.sh 'pkill java'"
    )
  done

  message "${type} benchmark: ${app} finished"
done
