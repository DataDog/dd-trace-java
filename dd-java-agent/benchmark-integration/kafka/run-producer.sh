#!/usr/bin/env bash
# Producer benchmark: kafka-producer-perf-test at a fixed offered rate under each variant, reporting
# latency percentiles. A fixed rate (not max throughput) keeps the latency numbers honest.
#
#   ./run-producer.sh NoAgent master=path/a.jar candidate=path/b.jar ...
#
# Env: RATE (default 100000 records/s), RECORDS (3000000), HEAP (256m), REPS (5), RESULTS_DIR.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"
require_kafka
[ "$#" -gt 0 ] || { echo "usage: $0 NoAgent label=agent.jar ..." >&2; exit 1; }

results="${RESULTS_DIR:-$WORK_DIR/results/producer-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$results/runs" "$results/gclogs"
csv="$results/results.csv"
echo "rep,variant,records_per_sec,avg_ms,p50_ms,p95_ms,p99_ms,p999_ms,young_gcs" > "$csv"

variants=("$@")
n=${#variants[@]}
heap="${HEAP:-256m}"
for rep in $(seq 1 "${REPS:-5}"); do
  rot=$(((rep - 1) % n))
  order=("${variants[@]:$rot}" "${variants[@]:0:$rot}")
  for v in "${order[@]}"; do
    parse_variant "$v"
    tag="r$rep-$VARIANT_LABEL"
    gc_log="$results/gclogs/$tag.log"
    status=0
    KAFKA_HEAP_OPTS="-Xms$heap -Xmx$heap" \
      KAFKA_OPTS="-Xlog:gc:file=$gc_log $(variant_opts "$VARIANT_JAR")" \
      "$KAFKA_HOME/bin/kafka-producer-perf-test.sh" --topic "$PRODUCE_TOPIC" \
      --num-records "${RECORDS:-3000000}" --record-size "${RECORD_SIZE:-100}" \
      --throughput "${RATE:-100000}" --producer-props "bootstrap.servers=$BOOTSTRAP" \
      > "$results/runs/$tag.out" 2>&1 || status=$?
    # Only the final summary carries percentiles; periodic progress lines also say "records sent".
    line="$(grep 'records sent.*99.9th' "$results/runs/$tag.out" | tail -1 || true)"
    if [ "$status" -ne 0 ] || [ -z "$line" ]; then
      log "FAIL $tag: exit $status (see $results/runs/$tag.out)"
      echo "$rep,$VARIANT_LABEL,,,,,,," >> "$csv"
      continue
    fi
    # "N records sent, R records/sec (..), A ms avg latency, M ms max latency, p50, p95, p99, p99.9"
    stats="$(echo "$line" | sed -E 's/.*records sent, ([0-9.]+) records\/sec.*, ([0-9.]+) ms avg latency, [0-9.]+ ms max latency, ([0-9]+) ms 50th, ([0-9]+) ms 95th, ([0-9]+) ms 99th, ([0-9]+) ms 99.9th.*/\1,\2,\3,\4,\5,\6/')"
    young="$(grep -c 'Pause Young' "$gc_log" 2>/dev/null || true)"
    echo "$rep,$VARIANT_LABEL,$stats,${young:-0}" >> "$csv"
    log "$tag: $stats"
  done
done
log "results: $csv"
java "$script_dir/Analyze.java" "$csv"
