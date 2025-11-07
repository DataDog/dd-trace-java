#!/usr/bin/env bash

set -e

start_server() {
  local VARIANT=$1
  local JAVA_OPTS=$2

  if [ -n "$CI_JOB_TOKEN" ]; then
    # Inside BP, so we can assume 24 CPU cores available and set CPU affinity
    CPU_AFFINITY_APP=$3
  else
    CPU_AFFINITY_APP=""
  fi

  mkdir -p "${LOGS_DIR}/${VARIANT}"
  ${CPU_AFFINITY_APP}java ${JAVA_OPTS} -Xms3G -Xmx3G -jar ${INSECURE_BANK} &> ${LOGS_DIR}/${VARIANT}/insecure-bank.log &PID=$!
  echo "${CPU_AFFINITY_APP}java ${JAVA_OPTS} -Xms3G -Xmx3G -jar ${INSECURE_BANK} &> ${LOGS_DIR}/${VARIANT}/insecure-bank.log [PID=$PID]"
}

start_server "no_agent" "-Dserver.port=8080" "taskset -c 47 " &
start_server "tracing" "-javaagent:${TRACER} -Dserver.port=8081" "taskset -c 46 " &
start_server "profiling" "-javaagent:${TRACER} -Ddd.profiling.enabled=true -Dserver.port=8082" "taskset -c 45 " &
start_server "iast" "-javaagent:${TRACER} -Ddd.iast.enabled=true -Dserver.port=8083" "taskset -c 44 " &
start_server "iast_GLOBAL" "-javaagent:${TRACER} -Ddd.iast.enabled=true -Ddd.iast.context.mode=GLOBAL -Dserver.port=8084" "taskset -c 43 " &
start_server "iast_FULL" "-javaagent:${TRACER} -Ddd.iast.enabled=true -Ddd.iast.detection.mode=FULL -Dserver.port=8085" "taskset -c 42 " &

wait
