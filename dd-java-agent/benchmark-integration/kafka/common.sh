#!/usr/bin/env bash
# Shared settings for the Kafka macro benchmark. Sourced by the other scripts; override via env.

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

KAFKA_VERSION="${KAFKA_VERSION:-3.9.1}"
SCALA_VERSION="${SCALA_VERSION:-2.13}"
WORK_DIR="${KAFKA_BENCH_DIR:-$script_dir/build}"
KAFKA_HOME="$WORK_DIR/kafka_${SCALA_VERSION}-${KAFKA_VERSION}"
BOOTSTRAP="${KAFKA_BOOTSTRAP:-localhost:9092}"
TRACE_AGENT_PORT="${TRACE_AGENT_PORT:-8126}"
CONSUME_TOPIC="${CONSUME_TOPIC:-bench-consume}"
PRODUCE_TOPIC="${PRODUCE_TOPIC:-bench-produce}"

# One JDK for the broker and every variant (17+); BENCH_JAVA_HOME wins, then JAVA_17_HOME, then JAVA_HOME.
bench_java_home="${BENCH_JAVA_HOME:-${JAVA_17_HOME:-${JAVA_HOME:-}}}"
if [ -n "$bench_java_home" ]; then
  export JAVA_HOME="$bench_java_home"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

log() { echo "[kafka-bench] $*" >&2; }

# JVM options for a variant: "NoAgent" or the path to an agent jar. DSM is off unless DSM=true, and
# AGENT_EXTRA_OPTS is appended (e.g. -Ddd.trace.experimental.dense.tags.enabled=true).
variant_opts() {
  local jar="$1"
  if [ "$jar" = "NoAgent" ]; then
    return
  fi
  echo "-javaagent:$jar -Ddd.trace.agent.port=$TRACE_AGENT_PORT -Ddd.service=kafka-bench" \
    "-Ddd.data.streams.enabled=${DSM:-false} -Ddatadog.slf4j.simpleLogger.defaultLogLevel=warn" \
    "${AGENT_EXTRA_OPTS:-}"
}

# Splits a "label=path/to/agent.jar" or "NoAgent" argument into VARIANT_LABEL / VARIANT_JAR.
parse_variant() {
  case "$1" in
    NoAgent) VARIANT_LABEL=noagent; VARIANT_JAR=NoAgent ;;
    *=*) VARIANT_LABEL="${1%%=*}"; VARIANT_JAR="${1#*=}" ;;
    *) VARIANT_LABEL="$(basename "$1" .jar)"; VARIANT_JAR="$1" ;;
  esac
  if [ "$VARIANT_JAR" != NoAgent ] && [ ! -f "$VARIANT_JAR" ]; then
    log "ERROR: agent jar not found: $VARIANT_JAR"
    exit 1
  fi
}

require_kafka() {
  if [ ! -x "$KAFKA_HOME/bin/kafka-server-start.sh" ]; then
    log "ERROR: Kafka not found at $KAFKA_HOME; run ./fetch-kafka.sh first"
    exit 1
  fi
}
