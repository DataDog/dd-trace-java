#!/usr/bin/env bash
# Consumer benchmark: kafka-consumer-perf-test reads the pre-filled topic under each variant. Every
# record carries trace headers, so each one goes through extraction and gets a consume span; the
# consumer itself does almost no work per record, which makes this a sensitive tracer-cost probe.
#
#   ./run-consumer.sh NoAgent master=path/a.jar candidate=path/b.jar ...
#
# Each variant runs in a fresh JVM; variant order rotates every rep so drift spreads evenly. GC is
# logged per run. Env: HEAPS (default "256m 128m 64m"), REPS (5), MESSAGES (10000000), RESULTS_DIR.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"
require_kafka
[ "$#" -gt 0 ] || { echo "usage: $0 NoAgent label=agent.jar ..." >&2; exit 1; }

results="${RESULTS_DIR:-$WORK_DIR/results/consumer-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$results/runs" "$results/gclogs"
csv="$results/results.csv"
echo "heap,rep,variant,fetch_msgs_per_sec,msgs,young_gcs,full_gcs" > "$csv"

variants=("$@")
n=${#variants[@]}
pass=0
for heap in ${HEAPS:-256m 128m 64m}; do
  for rep in $(seq 1 "${REPS:-5}"); do
    rot=$((pass % n)); pass=$((pass + 1))
    order=("${variants[@]:$rot}" "${variants[@]:0:$rot}")
    for v in "${order[@]}"; do
      parse_variant "$v"
      tag="$heap-r$rep-$VARIANT_LABEL"
      gc_log="$results/gclogs/$tag.log"
      KAFKA_HEAP_OPTS="-Xms$heap -Xmx$heap" \
        KAFKA_OPTS="-Xlog:gc:file=$gc_log $(variant_opts "$VARIANT_JAR")" \
        "$KAFKA_HOME/bin/kafka-consumer-perf-test.sh" --bootstrap-server "$BOOTSTRAP" \
        --topic "$CONSUME_TOPIC" --messages "${MESSAGES:-10000000}" --group "bench-$tag-$RANDOM" \
        --timeout 180000 > "$results/runs/$tag.out" 2>&1 || true
      # Last stats line: start, end, MB, MB/s, nMsg, nMsg/s, rebalance ms, fetch ms, fetch MB/s, fetch nMsg/s
      line="$(grep -E '^[0-9]{4}-' "$results/runs/$tag.out" | tail -1 || true)"
      if [ -z "$line" ]; then
        log "FAIL $tag (see $results/runs/$tag.out)"
        echo "$heap,$rep,$VARIANT_LABEL,,,," >> "$csv"
        continue
      fi
      rate="$(echo "$line" | awk -F', ' '{print $10}')"
      msgs="$(echo "$line" | awk -F', ' '{print $5}')"
      young="$(grep -c 'Pause Young' "$gc_log" 2>/dev/null || true)"
      full="$(grep -c 'Pause Full' "$gc_log" 2>/dev/null || true)"
      echo "$heap,$rep,$VARIANT_LABEL,$rate,$msgs,${young:-0},${full:-0}" >> "$csv"
      log "$tag: $rate msgs/s"
    done
  done
done
log "results: $csv"
python3 -I "$script_dir/analyze.py" "$csv"
