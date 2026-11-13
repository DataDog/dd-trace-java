#!/usr/bin/env bash
set -x

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]:-$0}")" &>/dev/null && pwd 2>/dev/null)"
TRACERS_PATH="$(pwd)/tracers"
SOURCE_PATH="${TRACERS_PATH}/source"
BENCHMARK_PATH="${SOURCE_PATH}/benchmark"
OUTPUT_DIR="${ARTIFACTS_DIR}"
TRACER="${TRACERS_PATH}/dd-java-agent-${BUILD_TYPE}.jar"

mkdir -p ${OUTPUT_DIR}

java -javaagent:${TRACER} \
  -Ddatadog.slf4j.simpleLogger.log.com.datadog.debugger=debug \
  -Ddd.dynamic.instrumentation.enabled=true \
  -Ddd.dynamic.instrumentation.probe.file=${SCRIPT_DIR}/probes.json \
  -XX:StartFlightRecording=filename=${OUTPUT_DIR}/petclinic-${BUILD_TYPE}.jfr,settings=default \
  -XX:MetaspaceSize=256M \
  -Xms16G -Xmx16G -Xmn15G -XX:SurvivorRatio=128 \
  -XX:ParallelGCThreads=4 \
  -jar ${PETCLINIC}
