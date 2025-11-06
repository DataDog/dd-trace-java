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

start_server "no_agent" "-Dserver.port=8086" "taskset -c 48 " &
start_server "tracing" "-javaagent:${TRACER} -Dserver.port=8087" "taskset -c 49 " &
start_server "profiling" "-javaagent:${TRACER} -Ddd.profiling.enabled=true -Dserver.port=8088" "taskset -c 50 " &
start_server "iast" "-javaagent:${TRACER} -Ddd.iast.enabled=true -Dserver.port=8089" "taskset -c 51 " &
start_server "iast_GLOBAL" "-javaagent:${TRACER} -Ddd.iast.enabled=true -Ddd.iast.context.mode=GLOBAL -Dserver.port=8090" "taskset -c 52 " &
start_server "iast_FULL" "-javaagent:${TRACER} -Ddd.iast.enabled=true -Ddd.iast.detection.mode=FULL -Dserver.port=8091" "taskset -c 53 " &

wait
