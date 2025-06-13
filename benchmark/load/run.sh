#!/usr/bin/env bash
set -eu

if [ -n "$CI_JOB_TOKEN" ]; then
  # Inside BP, so we can assume 24 CPU cores available and set CPU affinity
  export CPU_AFFINITY_K6="taskset -c 25-26 "
  export CPU_AFFINITY_APP="taskset -c 27-47 "
else
  export CPU_AFFINITY_K6=""
  export CPU_AFFINITY_APP=""
fi


source "${UTILS_DIR}/update-java-version.sh" 17

if [ -n "$CI_JOB_TOKEN" ]; then
  # Inside BP, so we can assume 24 CPU cores available and set CPU affinity
  export CPU_AFFINITY_K6="taskset -c 24-30 "
else
  export CPU_AFFINITY_K6=""
fi

"${UTILS_DIR}/run-sirun-benchmarks.sh" "$@"
