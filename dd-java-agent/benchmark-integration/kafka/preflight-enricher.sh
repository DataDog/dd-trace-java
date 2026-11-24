#!/usr/bin/env bash
# Verifies the enricher continues the trace end to end: an agent-instrumented producer writes records
# with trace headers, the enricher under the agent reads and re-produces them, and every output
# record must carry its input record's trace id with a new span id (the enricher's produce span).
# Run once per agent build before measuring, with the ENRICH_COPY_HEADERS the benchmark will use.
#
#   ./preflight-enricher.sh path/to/dd-java-agent.jar
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"
require_kafka

agent_jar="${1:?usage: $0 path/to/dd-java-agent.jar}"
copy_headers="${ENRICH_COPY_HEADERS:-false}" # check the header mode the benchmark will measure
records=20
in_topic="bench-preflight-in-$RANDOM"
out_topic="bench-preflight-out-$RANDOM"
out_dir="$WORK_DIR/preflight"
mkdir -p "$out_dir"

# Delete whatever this run created, however it exits.
created_topics=()
cleanup() {
  # The +"..." form keeps bash 3.2 with set -u from rejecting an empty array.
  for topic in ${created_topics[@]+"${created_topics[@]}"}; do
    "$KAFKA_HOME/bin/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" --delete --topic "$topic" \
      >/dev/null 2>&1 || log "WARN: could not delete topic $topic"
  done
}
trap cleanup EXIT

for topic in "$in_topic" "$out_topic"; do
  "$KAFKA_HOME/bin/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" --create --topic "$topic" \
    --partitions 1 --replication-factor 1 >/dev/null
  created_topics+=("$topic")
done
KAFKA_OPTS="$(variant_opts "$agent_jar")" "$KAFKA_HOME/bin/kafka-producer-perf-test.sh" \
  --topic "$in_topic" --num-records "$records" --record-size 100 --throughput -1 \
  --producer-props "bootstrap.servers=$BOOTSTRAP" >/dev/null 2>&1

# shellcheck disable=SC2046 # variant_opts is a list of JVM options
java "-Dlog4j.configuration=file:$KAFKA_HOME/config/tools-log4j.properties" \
  $(variant_opts "$agent_jar") -cp "$KAFKA_HOME/libs/*" "$script_dir/Enricher.java" \
  "$BOOTSTRAP" "$in_topic" "$out_topic" "$records" "preflight-$RANDOM" "$copy_headers" \
  > "$out_dir/enricher.log" 2>&1 || {
  log "FAIL: enricher did not complete; see $out_dir/enricher.log"
  exit 1
}

# Prints "trace-id span-id" for each record of a topic, in offset order (the topics have one
# partition). Datadog style if injected, else W3C traceparent (e.g. with tracecontext-only injection
# configured through AGENT_EXTRA_OPTS). A record without trace headers prints "- -".
read_ids() {
  local topic="$1" headers="$out_dir/headers-$1.txt"
  # A deadline, so a broker that stops serving records fails the check instead of hanging it.
  "$KAFKA_HOME/bin/kafka-console-consumer.sh" --bootstrap-server "$BOOTSTRAP" --topic "$topic" \
    --from-beginning --max-messages "$records" --timeout-ms 30000 \
    --property print.headers=true --property print.value=false \
    > "$headers" 2> "$out_dir/consumer-$topic.log" || true
  local line trace span
  while IFS= read -r line; do
    trace="$(grep -oE 'x-datadog-trace-id:[0-9]+' <<<"$line" | head -1 | cut -d: -f2 || true)"
    span="$(grep -oE 'x-datadog-parent-id:[0-9]+' <<<"$line" | head -1 | cut -d: -f2 || true)"
    if [ -z "$trace" ]; then
      local tp
      tp="$(grep -oE 'traceparent:00-[0-9a-f]{32}-[0-9a-f]{16}' <<<"$line" | head -1 || true)"
      trace="$(cut -d- -f2 <<<"$tp")"
      span="$(cut -d- -f3 <<<"$tp")"
    fi
    echo "${trace:--} ${span:--}"
  done < "$headers"
}
# Plain read loops rather than mapfile, which macOS's bash 3.2 lacks.
in_ids=()
while IFS= read -r ids; do in_ids+=("$ids"); done < <(read_ids "$in_topic")
out_ids=()
while IFS= read -r ids; do out_ids+=("$ids"); done < <(read_ids "$out_topic")

if [ "${#in_ids[@]}" -ne "$records" ] || [ "${#out_ids[@]}" -ne "$records" ]; then
  log "FAIL: read ${#in_ids[@]} input and ${#out_ids[@]} output records, expected $records;" \
    "see $out_dir/consumer-*.log"
  exit 1
fi
failures=0
for i in $(seq 0 $((records - 1))); do
  read -r in_trace in_span <<<"${in_ids[$i]}"
  read -r out_trace out_span <<<"${out_ids[$i]}"
  if [ "$in_trace" = - ] || [ "$out_trace" = - ]; then
    log "FAIL record $i: missing trace headers (input '$in_trace', output '$out_trace')"
  elif [ "$in_trace" != "$out_trace" ]; then
    log "FAIL record $i: enricher started a new trace (input $in_trace, output $out_trace)"
  elif [ "$in_span" = "$out_span" ]; then
    # The output must carry the enricher's produce span, not just the input's forwarded headers.
    log "FAIL record $i: output headers still name the input's span ($in_span)"
  else
    continue
  fi
  failures=$((failures + 1))
done
if [ "$failures" -gt 0 ]; then
  log "FAIL: $failures of $records records (copy-headers=$copy_headers); headers in $out_dir"
  exit 1
fi
log "PASS: enricher continues the trace on all $records records (copy-headers=$copy_headers)"
