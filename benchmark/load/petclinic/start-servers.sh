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
  ${CPU_AFFINITY_APP}java ${JAVA_OPTS} -Xms2G -Xmx2G -jar ${PETCLINIC} &> ${LOGS_DIR}/${VARIANT}/petclinic.log &PID=$!
  echo "${CPU_AFFINITY_APP}java ${JAVA_OPTS} -Xms2G -Xmx2G -jar ${PETCLINIC} &> ${LOGS_DIR}/${VARIANT}/petclinic.log [PID=$!]"
}

start_server "no_agent" "-Dserver.port=8080" "taskset -c 31-32 " &
start_server "tracing" "-javaagent:${TRACER} -Dserver.port=8081" "taskset -c 33-34 " &
start_server "profiling" "-javaagent:${TRACER} -Ddd.profiling.enabled=true -Dserver.port=8082" "taskset -c 35-36 " &
start_server "appsec" "-javaagent:${TRACER} -Ddd.appsec.enabled=true -Dserver.port=8083" "taskset -c 37-38 " &
start_server "iast" "-javaagent:${TRACER} -Ddd.iast.enabled=true -Dserver.port=8084" "taskset -c 39-40 " &
start_server "code_origins" "-javaagent:${TRACER} -Ddd.code.origin.for.spans.enabled=true -Dserver.port=8085" "taskset -c 41-42 " &
start_server "config_sources" "-javaagent:${TRACER} -Ddd.service=myservice -Ddd.env=myenv -Ddd.version=1.2.3" "taskset -c 43-44 " &

wait
