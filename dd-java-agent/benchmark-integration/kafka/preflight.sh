#!/usr/bin/env bash
# Verifies the setup measures what it claims: the agent-instrumented producer injects trace headers,
# and an agent-instrumented consumer continues the producer's trace (consume span parented by the
# record's parent id). Run once per agent build before measuring.
#
#   ./preflight.sh path/to/dd-java-agent.jar
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"
require_kafka

agent_jar="${1:?usage: $0 path/to/dd-java-agent.jar}"
topic="bench-preflight-$RANDOM"
out_dir="$WORK_DIR/preflight"
mkdir -p "$out_dir"

"$KAFKA_HOME/bin/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" --create --topic "$topic" \
  --partitions 1 --replication-factor 1 >/dev/null
KAFKA_OPTS="$(variant_opts "$agent_jar")" "$KAFKA_HOME/bin/kafka-producer-perf-test.sh" \
  --topic "$topic" --num-records 20 --record-size 100 --throughput -1 \
  --producer-props "bootstrap.servers=$BOOTSTRAP" >/dev/null 2>&1

headers="$("$KAFKA_HOME/bin/kafka-console-consumer.sh" --bootstrap-server "$BOOTSTRAP" --topic "$topic" \
  --from-beginning --max-messages 1 --property print.headers=true --property print.value=false 2>/dev/null)"
parent_id="$(echo "$headers" | grep -oE 'x-datadog-parent-id:[0-9]+' | cut -d: -f2 || true)"
if [ -z "$parent_id" ] || ! echo "$headers" | grep -q 'traceparent:'; then
  log "FAIL: producer did not inject trace headers: $headers"
  exit 1
fi
log "producer injects trace headers (first record parent id $parent_id)"

# The later log-level property overrides variant_opts' warn level, so the span debug lines appear.
KAFKA_OPTS="$(variant_opts "$agent_jar") -Ddd.trace.debug=true -Ddatadog.slf4j.simpleLogger.defaultLogLevel=debug" \
  "$KAFKA_HOME/bin/kafka-consumer-perf-test.sh" --bootstrap-server "$BOOTSTRAP" --topic "$topic" \
  --messages 20 --group "preflight-$RANDOM" --timeout 30000 > "$out_dir/consumer.log" 2>&1
if grep -q "p_id=$parent_id" "$out_dir/consumer.log"; then
  log "PASS: consumer continues the producer's trace"
else
  log "FAIL: no consume span parented by $parent_id; see $out_dir/consumer.log"
  exit 1
fi
