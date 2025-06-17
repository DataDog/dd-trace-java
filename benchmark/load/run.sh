#!/usr/bin/env bash

set -ex

function message() {
  echo "$(date +"%T"): $1"
}

type=$1

if [ -n "$CI_JOB_TOKEN" ]; then
  # Inside BP, so we can assume 24 CPU cores on the second socket available and set CPU affinity
  export CPU_AFFINITY_K6="taskset -c 24-30 "
else
  export CPU_AFFINITY_K6=""
fi

source "${UTILS_DIR}/update-java-version.sh" 17

for app in *; do
  if [[ ! -d "${app}" ]]; then
    continue
  fi

  message "${type} benchmark: ${app} started"

  export OUTPUT_DIR="${ARTIFACTS_DIR}/${type}/${app}"
  mkdir -p ${OUTPUT_DIR}

  if [ "${app}" == "petclinic" ]; then
    HEALTHCHECK_URL=http://localhost:8080
  elif [ "${app}" == "insecure-bank" ]; then
    HEALTHCHECK_URL=http://localhost:8080/login
  else
    echo "Unknown app ${app}"
    exit 1
  fi

  bash -c "${UTILS_DIR}/../${type}/${app}/start-servers.sh" &
  (
    cd ${app} &&
    bash -c "${CPU_AFFINITY_K6}${UTILS_DIR}/run-k6-load-test.sh ${HEALTHCHECK_URL} ${OUTPUT_DIR} 'pkill java'"
  )

  message "${type} benchmark: ${app} finished"
done
