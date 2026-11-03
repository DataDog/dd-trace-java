#!/usr/bin/env bash
# Verifies the enricher continues the trace end to end: an agent-instrumented producer writes records
# with trace headers, the enricher under the agent reads and re-produces them, and each output
# record must carry the same trace id as its input record. Run once per agent build before measuring.
#
#   ./preflight-enricher.sh path/to/dd-java-agent.jar
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"
require_kafka

agent_jar="${1:?usage: $0 path/to/dd-java-agent.jar}"
in_topic="bench-preflight-in-$RANDOM"
out_topic="bench-preflight-out-$RANDOM"
out_dir="$WORK_DIR/preflight"
mkdir -p "$out_dir"

for topic in "$in_topic" "$out_topic"; do
  "$KAFKA_HOME/bin/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" --create --topic "$topic" \
    --partitions 1 --replication-factor 1 >/dev/null
done
KAFKA_OPTS="$(variant_opts "$agent_jar")" "$KAFKA_HOME/bin/kafka-producer-perf-test.sh" \
  --topic "$in_topic" --num-records 20 --record-size 100 --throughput -1 \
  --producer-props "bootstrap.servers=$BOOTSTRAP" >/dev/null 2>&1

# shellcheck disable=SC2046 # variant_opts is a list of JVM options
java "-Dlog4j.configuration=file:$KAFKA_HOME/config/tools-log4j.properties" \
  $(variant_opts "$agent_jar") -cp "$KAFKA_HOME/libs/*" "$script_dir/Enricher.java" \
  "$BOOTSTRAP" "$in_topic" "$out_topic" 20 "preflight-$RANDOM" > "$out_dir/enricher.log" 2>&1 || {
  log "FAIL: enricher did not complete; see $out_dir/enricher.log"
  exit 1
}

first_trace_id() {
  "$KAFKA_HOME/bin/kafka-console-consumer.sh" --bootstrap-server "$BOOTSTRAP" --topic "$1" \
    --from-beginning --max-messages 1 --property print.headers=true --property print.value=false \
    2>/dev/null | grep -oE 'x-datadog-trace-id:[0-9]+' | cut -d: -f2 || true
}
in_id="$(first_trace_id "$in_topic")"
out_id="$(first_trace_id "$out_topic")"
for topic in "$in_topic" "$out_topic"; do
  "$KAFKA_HOME/bin/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" --delete --topic "$topic" \
    >/dev/null 2>&1 || true
done
if [ -z "$in_id" ] || [ -z "$out_id" ]; then
  log "FAIL: missing trace headers (input trace id '$in_id', output trace id '$out_id')"
  exit 1
fi
if [ "$in_id" != "$out_id" ]; then
  log "FAIL: enricher started a new trace (input $in_id, output $out_id)"
  exit 1
fi
log "PASS: enricher continues the trace end to end (trace id $in_id)"
