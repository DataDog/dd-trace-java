#!/usr/bin/env bash
# Creates the benchmark topics and fills the consume topic using an agent-instrumented producer, so
# every record carries the trace headers a real upstream service would inject (Datadog, W3C
# traceparent/tracestate). The consumer runs then exercise extraction on every record.
#
#   ./prefill.sh path/to/dd-java-agent.jar
#
# PREFILL_RECORDS (default 10000000) and RECORD_SIZE (default 100) size the topic.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"
require_kafka

agent_jar="${1:?usage: $0 path/to/dd-java-agent.jar}"
[ -f "$agent_jar" ] || { log "ERROR: agent jar not found: $agent_jar"; exit 1; }

for topic in "$CONSUME_TOPIC" "$PRODUCE_TOPIC"; do
  "$KAFKA_HOME/bin/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" --create --if-not-exists \
    --topic "$topic" --partitions 1 --replication-factor 1 >&2
done

records="${PREFILL_RECORDS:-10000000}"
log "producing $records records to $CONSUME_TOPIC with trace headers"
KAFKA_HEAP_OPTS="-Xmx1g" KAFKA_OPTS="$(variant_opts "$agent_jar")" \
  "$KAFKA_HOME/bin/kafka-producer-perf-test.sh" --topic "$CONSUME_TOPIC" \
  --num-records "$records" --record-size "${RECORD_SIZE:-100}" --throughput -1 \
  --producer-props "bootstrap.servers=$BOOTSTRAP" linger.ms=5 batch.size=65536 2>/dev/null | tail -1 >&2
