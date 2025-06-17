#!/usr/bin/env bash

set -exu

local type=$1
local app=$2

export OUTPUT_DIR="${REPORTS_DIR}/${type}/${app}"

if [ -n "$CI_JOB_TOKEN" ]; then
  # Inside BP, so we can assume 24 CPU cores on the second socket available and set CPU affinity
  export CPU_AFFINITY_K6="taskset -c 24-30 "
else
  export CPU_AFFINITY_K6=""
fi

source "${UTILS_DIR}/update-java-version.sh" 17

if [ "${app}" == "petclinic" ]; then
  HEALTHCHECK_URL=http://localhost:8080
elif [ "${app}" == "insecure-bank" ]; then
  HEALTHCHECK_URL=http://localhost:8080/login
else
  echo "Unknown app ${app}"
  exit 1
fi

bash -c "${UTILS_DIR}/../${type}/${app}/start-servers.sh"
bash -c "${CPU_AFFINITY_K6}${UTILS_DIR}/run-k6-load-test.sh ${HEALTHCHECK_URL} ${OUTPUT_DIR} 'pkill java'"
