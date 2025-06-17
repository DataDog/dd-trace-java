#!/usr/bin/env bash

set -e

function message() {
  echo "$(date +"%T"): $1"
}

type=$1

if [ -n "$CI_JOB_TOKEN" ]; then
  # Inside BP, so we can assume 24 CPU cores on the second socket available and set CPU affinity
  export CPU_AFFINITY_K6="taskset -c 24-27 "
else
  export CPU_AFFINITY_K6=""
fi

source "${UTILS_DIR}/update-java-version.sh" 17

for app in *; do
  if [[ ! -d "${app}" ]]; then
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
  elif [ "${app}" == "insecure-bank" ]; then
    HEALTHCHECK_URL=http://localhost:8082/login
  else
    echo "Unknown app ${app}"
    exit 1
  fi

  for i in $(seq 1 5); do
    bash -c "${UTILS_DIR}/../${type}/${app}/start-servers.sh" &
    (
      cd ${app} &&
      bash -c "${CPU_AFFINITY_K6}${UTILS_DIR}/run-k6-load-test.sh ${HEALTHCHECK_URL} 'pkill java'"
    )
  done

  message "${type} benchmark: ${app} finished"
done
