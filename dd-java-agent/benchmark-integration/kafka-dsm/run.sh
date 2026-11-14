#!/usr/bin/env bash
# usage: run.sh <baseline|apm-only|apm+dsm> [slug]
#
# Compiles and runs Workload.java in one JVM. Tracer scenarios attach DD_TRACE_JAVA_AGENT_JAR
# (e.g. ./gradlew :dd-java-agent:shadowJar output). Env overrides: see README.md.
set -euo pipefail
cd "$(dirname "$0")"

SCENARIO=${1:?usage: run.sh <baseline|apm-only|apm+dsm> [slug]}
SLUG=${2:-base}
case $SCENARIO in
  baseline|apm-only|apm+dsm) ;;
  *) echo "unknown scenario: $SCENARIO" >&2; exit 2 ;;
esac
# File/topic-safe scenario slug: apm+dsm -> apm-plus-dsm.
NAME=${SCENARIO//+/-plus-}
OUT_DIR=${OUT_DIR:-$PWD/build/out}
mkdir -p "$OUT_DIR"

export KAFKA_TOPIC=${KAFKA_TOPIC:-benchmark-topic-java-$NAME-$SLUG}
export EXPERIMENT_NAME=${EXPERIMENT_NAME:-$NAME}
export TIMEIT_OUTPUT=${TIMEIT_OUTPUT:-$OUT_DIR/java-$SLUG.$NAME.json}
# Must accept payloads: a dead port adds connection-error overhead to the tracer scenarios (see README).
export DD_TRACE_AGENT_URL=${DD_TRACE_AGENT_URL:-http://127.0.0.1:18127}

# kafka-clients + slf4j: the benchmarking-platform image ships them in /opt/kafka-clients.
if [ -z "${KAFKA_CLIENTS_DIR:-}" ]; then
  if [ -f /opt/kafka-clients/kafka-clients.jar ]; then
    KAFKA_CLIENTS_DIR=/opt/kafka-clients
  else
    KAFKA_CLIENTS_DIR=$PWD/build/kafka-clients
    maven=https://repo1.maven.org/maven2
    mkdir -p "$KAFKA_CLIENTS_DIR"
    for spec in kafka-clients:org/apache/kafka/kafka-clients/3.9.0/kafka-clients-3.9.0.jar \
                slf4j-api:org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar \
                slf4j-nop:org/slf4j/slf4j-nop/1.7.36/slf4j-nop-1.7.36.jar; do
      jar="$KAFKA_CLIENTS_DIR/${spec%%:*}.jar"
      if [ ! -f "$jar" ]; then
        curl -fsSL --retry 5 --retry-delay 2 --retry-connrefused -o "$jar.tmp" "$maven/${spec#*:}"
        mv "$jar.tmp" "$jar"
      fi
    done
  fi
fi
CLASSPATH="$KAFKA_CLIENTS_DIR/kafka-clients.jar:$KAFKA_CLIENTS_DIR/slf4j-api.jar:$KAFKA_CLIENTS_DIR/slf4j-nop.jar"

CLASSES_DIR=$PWD/build/classes
if [ ! -f "$CLASSES_DIR/Workload.class" ] || [ Workload.java -nt "$CLASSES_DIR/Workload.class" ]; then
  mkdir -p "$CLASSES_DIR"
  javac -cp "$CLASSPATH" -d "$CLASSES_DIR" Workload.java
fi

AGENT_ARGS=()
if [ "$SCENARIO" != baseline ]; then
  if [ ! -f "${DD_TRACE_JAVA_AGENT_JAR:-}" ]; then
    echo "DD_TRACE_JAVA_AGENT_JAR must point to a dd-java-agent jar, got '${DD_TRACE_JAVA_AGENT_JAR:-}'" >&2
    exit 2
  fi
  AGENT_ARGS=("-javaagent:$DD_TRACE_JAVA_AGENT_JAR")
fi

# Fixed heap for every scenario: a large default max heap made RSS move in heap-expansion steps.
JAVA_HEAP=${JAVA_HEAP:-1g}

case $SCENARIO in
  baseline) unset DD_DATA_STREAMS_ENABLED ;;
  apm-only) export DD_DATA_STREAMS_ENABLED=false ;;
  apm+dsm) export DD_DATA_STREAMS_ENABLED=true ;;
esac
exec java "-Xms$JAVA_HEAP" "-Xmx$JAVA_HEAP" ${AGENT_ARGS[@]+"${AGENT_ARGS[@]}"} \
  -cp "$CLASSES_DIR:$CLASSPATH" Workload
