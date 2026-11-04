#!/usr/bin/env bash
# Enricher benchmark: Enricher.java reads the pre-filled topic, appends a few bytes to each record and
# produces it to another topic, under each variant. Each record is extracted, gets a consume span,
# and its send gets a child produce span that injects headers, so this covers both sides of
# propagation on one trace, as in a stream-processing service.
#
#   ./run-enricher.sh NoAgent master=path/a.jar candidate=path/b.jar ...
#
# Each variant runs in a fresh JVM; variant order rotates every rep so drift spreads evenly. GC is
# logged per run. The default heaps start at 128m: at 64m the run is GC-bound with or without the
# agent (likely the producer's 32 MB send buffer, half of the heap).
# Env: HEAPS (default "512m 256m 128m"), REPS (5), MESSAGES (3000000),
# ENRICH_COPY_HEADERS (false; true forwards input headers, as Kafka Streams does), RESULTS_DIR.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"
require_kafka
[ "$#" -gt 0 ] || { echo "usage: $0 NoAgent label=agent.jar ..." >&2; exit 1; }

results="${RESULTS_DIR:-$WORK_DIR/results/enricher-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$results/runs" "$results/gclogs"
csv="$results/results.csv"
echo "heap,rep,variant,msgs_per_sec,msgs,young_gcs,full_gcs" > "$csv"

# Each run writes to its own output topic, deleted after the run (outside the measured window), so
# output doesn't pile up on disk across runs. Topic retention alone wouldn't do it: the broker only
# checks retention every 5 minutes by default.
kafka_topics() { "$KAFKA_HOME/bin/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" "$@"; }

variants=("$@")
n=${#variants[@]}
messages="${MESSAGES:-3000000}"
pass=0
for heap in ${HEAPS:-512m 256m 128m}; do
  for rep in $(seq 1 "${REPS:-5}"); do
    rot=$((pass % n)); pass=$((pass + 1))
    order=("${variants[@]:$rot}" "${variants[@]:0:$rot}")
    for v in "${order[@]}"; do
      parse_variant "$v"
      tag="$heap-r$rep-$VARIANT_LABEL"
      gc_log="$results/gclogs/$tag.log"
      status=0
      out_topic="$ENRICH_TOPIC-$tag-$RANDOM"
      kafka_topics --create --topic "$out_topic" --partitions 1 --replication-factor 1 \
        --config file.delete.delay.ms=1000 >/dev/null
      # shellcheck disable=SC2046 # variant_opts is a list of JVM options
      java "-Xms$heap" "-Xmx$heap" "-Xlog:gc:file=$gc_log" \
        "-Dlog4j.configuration=file:$KAFKA_HOME/config/tools-log4j.properties" \
        $(variant_opts "$VARIANT_JAR") -cp "$KAFKA_HOME/libs/*" "$script_dir/Enricher.java" \
        "$BOOTSTRAP" "$CONSUME_TOPIC" "$out_topic" "$messages" "bench-$tag-$RANDOM" \
        "${ENRICH_COPY_HEADERS:-false}" > "$results/runs/$tag.out" 2>&1 || status=$?
      kafka_topics --delete --topic "$out_topic" >/dev/null 2>&1 || true
      line="$(grep -E '^enriched [0-9]+ records' "$results/runs/$tag.out" | tail -1 || true)"
      if [ "$status" -ne 0 ] || [ -z "$line" ]; then
        log "FAIL $tag: exit $status (see $results/runs/$tag.out)"
        echo "$heap,$rep,$VARIANT_LABEL,,,," >> "$csv"
        continue
      fi
      # "enriched N records in T ms, R records/s"
      msgs="$(echo "$line" | awk '{print $2}')"
      rate="$(echo "$line" | awk '{print $7}')"
      young="$(grep -c 'Pause Young' "$gc_log" 2>/dev/null || true)"
      full="$(grep -c 'Pause Full' "$gc_log" 2>/dev/null || true)"
      echo "$heap,$rep,$VARIANT_LABEL,$rate,$msgs,${young:-0},${full:-0}" >> "$csv"
      log "$tag: $rate msgs/s"
    done
  done
done
log "results: $csv"
java "$script_dir/Analyze.java" "$csv"
