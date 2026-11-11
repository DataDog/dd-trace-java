#!/usr/bin/env bash
#
# One command to download, run, and analyze Spring PetClinic under the Datadog Java agent -- the
# self-serve macro benchmark. Sweeps one or more agent variants against a NoAgent baseline, captures
# a JFR per variant, and prints throughput + the tracer-overhead split (CPU + sampled allocation).
#
# Usage:
#   ./run-petclinic.sh [variant ...]
#
# Each variant resolves to an agent jar:
#   <path/to/agent.jar>   use that jar as-is
#   current               build the current checkout's shadow jar
#   <version> (e.g. 1.62.0)   download that release from Maven Central
#
# With no variants it defaults to `current`. A NoAgent baseline is always run first.
#
# By default the agent variants send traces to a local Agent stub (agent-stub/AgentStub.java) on
# PETCLINIC_STUB_PORT (8137), and the sweep aborts if a variant delivered none. A slow or rejecting
# local Agent makes the tracer hold traces in the heap, which changes what's measured, so the stub
# takes it out. PETCLINIC_AGENT=real uses the Agent on TRACE_AGENT_PORT instead.
# By default every request also carries upstream trace headers (wrk-upstream-headers.lua), as a
# service called by another traced service would see; PETCLINIC_UPSTREAM_HEADERS=false turns that off.
# Examples:
#   ./run-petclinic.sh                       # NoAgent vs the current branch
#   ./run-petclinic.sh 1.54.0 1.58.0 current # a version sweep for the historic curve
#
# Requires: git, a JDK (jfr CLI + Maven wrapper), wrk, nc, curl.

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
agents_dir="$script_dir/build/agents"
mkdir -p "$agents_dir"

log() { echo "[run-petclinic] $*" >&2; }

# PetClinic (Spring Boot 3) requires JDK 17+, and the jfr CLI requires 11+. Use BENCH_JAVA_HOME, else
# JAVA_17_HOME, else JAVA_HOME, else the JDK of the java on PATH, and put it first on PATH so java /
# jfr / mvnw are all consistent. All variants in a sweep run under this one JDK -- keep it fixed for
# comparability.
bench_java_home="${BENCH_JAVA_HOME:-${JAVA_17_HOME:-${JAVA_HOME:-}}}"
if [ -z "$bench_java_home" ] && command -v java >/dev/null 2>&1; then
  bench_java_home=$(java -XshowSettings:properties -version 2>&1 | awk -F' = ' '/java.home/ { print $2 }')
fi
bench_java_major=""
if [ -n "$bench_java_home" ] && [ -x "$bench_java_home/bin/java" ]; then
  bench_java_major=$("$bench_java_home/bin/java" -XshowSettings:properties -version 2>&1 \
    | awk -F' = ' '/java.specification.version/ { v = $2; sub(/^1\./, "", v); print v }')
fi
if [ -z "$bench_java_major" ] || [ "$bench_java_major" -lt 17 ] || [ ! -x "$bench_java_home/bin/jfr" ]; then
  echo "[run-petclinic] ERROR: need a JDK 17+ with the jfr CLI (found '${bench_java_home:-none}', version '${bench_java_major:-unknown}')." >&2
  echo "[run-petclinic]   set BENCH_JAVA_HOME, JAVA_17_HOME or JAVA_HOME to one" >&2
  exit 1
fi
export JAVA_HOME="$bench_java_home"
export PATH="$JAVA_HOME/bin:$PATH"
log "using JDK: $(java -version 2>&1 | head -1)  ($JAVA_HOME)"

# Resolve a variant token to a jar path (echoes the path). "NoAgent" passes through as a baseline
# marker that run-perf-test.sh understands.
resolve_variant() {
  local v="$1"
  if [ "$v" = "NoAgent" ]; then
    echo "NoAgent"; return
  fi
  if [ -f "$v" ]; then
    # Absolute, since the benchmark runs from script_dir.
    echo "$(cd "$(dirname "$v")" && pwd)/$(basename "$v")"; return
  fi
  if [ "$v" = "current" ]; then
    log "building current shadow jar (:dd-java-agent:shadowJar)"
    # Redirect the build's stdout to stderr: resolve_variant's stdout is captured as the jar path by
    # the caller, so any gradle output on stdout would corrupt it (that broke the first sweep).
    ( cd "$repo_root" && ./gradlew -q :dd-java-agent:shadowJar >&2 ) \
      || { log "ERROR: shadow jar build failed"; exit 1; }
    local jar
    jar=$(ls -t "$repo_root"/dd-java-agent/build/libs/dd-java-agent-*.jar 2>/dev/null \
      | grep -v -- '-sources' | grep -v -- '-javadoc' | head -1 || true)
    [ -n "$jar" ] || { log "ERROR: shadow jar not found after build"; exit 1; }
    echo "$jar"; return
  fi
  # Otherwise treat as a released version -> download from GitHub releases (canonical dd-trace-java
  # release location; the tag is v-prefixed, the asset is not). Maven Central is the fallback.
  local dest="$agents_dir/dd-java-agent-$v.jar"
  if [ ! -f "$dest" ]; then
    local gh="https://github.com/DataDog/dd-trace-java/releases/download/v$v/dd-java-agent-$v.jar"
    local mvn="https://repo1.maven.org/maven2/com/datadoghq/dd-java-agent/$v/dd-java-agent-$v.jar"
    log "downloading dd-java-agent $v from GitHub releases"
    if ! curl -fsSL "$gh" -o "$dest" 2>/dev/null; then
      log "GitHub release miss for $v -- falling back to Maven Central"
      curl -fsSL "$mvn" -o "$dest" \
        || { log "ERROR: could not download version '$v' (GitHub: $gh | Maven: $mvn)"; rm -f "$dest"; exit 1; }
    fi
  fi
  echo "$dest"
}

variants=("$@")
[ ${#variants[@]} -eq 0 ] && variants=("current")
# Always lead with a NoAgent baseline unless the caller already did.
if [ "${variants[0]}" != "NoAgent" ]; then
  variants=("NoAgent" "${variants[@]}")
fi

log "resolving ${#variants[@]} variant(s): ${variants[*]}"
resolved=()
# A stable ID per variant, assigned before any rotation: its position in the list plus the token it
# was given as. Two builds can report the same version (a saved jar against `current`), so results
# are grouped by this ID, never by version.
variant_ids=()
for v in "${variants[@]}"; do
  # resolve_variant runs in a command substitution, where its `exit 1` only leaves the subshell, so
  # check its status here; otherwise a failed build or download would benchmark the wrong jar.
  jar=$(resolve_variant "$v") || exit 1
  resolved+=("$jar")
  # The ID goes into CSV rows, so keep only characters that can't split or quote a field.
  variant_name=$(basename "$v")
  variant_ids+=("v${#resolved[@]}:${variant_name//[!a-zA-Z0-9._-]/_}")
done

if [ "${PETCLINIC_AGENT:-stub}" = "stub" ]; then
  stub_port="${PETCLINIC_STUB_PORT:-8137}"
  if nc -z localhost "$stub_port" >/dev/null 2>&1; then
    log "ERROR: port $stub_port is in use; set PETCLINIC_STUB_PORT to a free port"
    exit 1
  fi
  java "$script_dir/agent-stub/AgentStub.java" "$stub_port" "$script_dir/agent-stub/agent-info.json" \
    > "$agents_dir/agent-stub.log" 2>&1 &
  stub_pid=$!
  trap 'kill "$stub_pid" 2>/dev/null || true' EXIT
  for _ in $(seq 1 30); do
    curl -sf -o /dev/null "http://127.0.0.1:$stub_port/info" && break
    sleep 1
  done
  curl -sf -o /dev/null "http://127.0.0.1:$stub_port/info" \
    || { log "ERROR: Agent stub did not start; see $agents_dir/agent-stub.log"; exit 1; }
  export TRACE_AGENT_PORT="$stub_port" EXPECT_AGENT=up
  export PERF_SINK_STATS_URL="http://127.0.0.1:$stub_port/_stats"
  log "Agent stub on :$stub_port"
fi
if [ "${PETCLINIC_UPSTREAM_HEADERS:-true}" = "true" ]; then
  export PERF_WRK_SCRIPT="${PERF_WRK_SCRIPT:-$script_dir/wrk-upstream-headers.lua}"
fi

log "fetching + building PetClinic"
petclinic_jar="$(bash "$script_dir/fetch-petclinic.sh")"
log "PetClinic jar: $petclinic_jar"
# Turn off Spring's resource chain (read by Spring Boot from the environment as
# spring.web.resources.chain.enabled). With it on, every Thymeleaf link costs a class path lookup
# that serializes requests on a class loader lock; see README.md. The server inherits this.
export SPRING_WEB_RESOURCES_CHAIN_ENABLED=false

# Heap-sweep dimension: run the full variant sweep once per heap size. PETCLINIC_HEAPS is a
# space-separated list (e.g. "64m 256m 512m"); default is a single 256m (no heap sweep).
# NOTE: total runtime is heaps x variants -- a heap list multiplies the wall clock.
heaps=(${PETCLINIC_HEAPS:-256m})

# Honor a pre-set PERF_SETTINGS (e.g. a smoke-test rc); otherwise use the PetClinic settings.
settings="${PERF_SETTINGS:-$script_dir/perf-test-petclinic-settings.rc}"
# Absolute, since the benchmark runs from script_dir.
[ -f "$settings" ] || { log "ERROR: settings file not found: $settings"; exit 1; }
settings="$(cd "$(dirname "$settings")" && pwd)/$(basename "$settings")"

results_dir="$script_dir/build/petclinic-results/results-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$results_dir"
combined_csv="$results_dir/throughput-grid.csv"

# PETCLINIC_RUNS passes per heap (default 1). Each pass starts a fresh JVM per variant, and the
# variant order (NoAgent included) rotates every pass, so drift over a long sweep -- thermals,
# background load -- spreads across variants instead of always landing on the same one. Each pass's
# artifacts go in heap-<h>/run-<n>/; the combined grid has a Run column, and with more than one pass
# a median-per-variant summary is printed at the end.
runs="${PETCLINIC_RUNS:-1}"
log "sweeping ${#resolved[@]} variant(s) x ${#heaps[@]} heap(s) [${heaps[*]}] x $runs run(s) x ${PETCLINIC_REPEATS:-1} repeat(s), JFR enabled, settings=$settings"
pass=0
for heap in "${heaps[@]}"; do
  for run in $(seq 1 "$runs"); do
    run_dir="$results_dir/heap-$heap/run-$run"
    mkdir -p "$run_dir"
    rot=$((pass % ${#resolved[@]})); pass=$((pass + 1))
    order=("${resolved[@]:$rot}" "${resolved[@]:0:$rot}")
    order_ids=("${variant_ids[@]:$rot}" "${variant_ids[@]:0:$rot}")
    rm -f /tmp/perf_*.jfr /tmp/perf_gc_*.log /tmp/perf_results.csv
    log "=== HEAP $heap, run $run/$runs ==="
    (
      cd "$script_dir"
      PERF_JFR=1 PERF_HEAP="$heap" PERF_REPEATS="${PETCLINIC_REPEATS:-1}" PERF_SETTINGS="$settings" \
        bash ./run-perf-test.sh jar "$petclinic_jar" "${order[@]}"
    )
    cp -f /tmp/perf_results.csv "$run_dir/" 2>/dev/null || true
    cp -f /tmp/perf_*.jfr /tmp/perf_gc_*.log "$run_dir/" 2>/dev/null || true

    # Fold this run into the combined grid CSV with leading Heap, Run and Variant columns.
    # run-perf-test.sh writes one row per jar, in argument order, so row i is order_ids[i].
    if [ -f /tmp/perf_results.csv ]; then
      [ -f "$combined_csv" ] || echo "Heap,Run,Variant,$(head -1 /tmp/perf_results.csv)" > "$combined_csv"
      tail -n +2 /tmp/perf_results.csv \
        | awk -v prefix="$heap,$run," -v ids="$(IFS='|'; echo "${order_ids[*]}")" \
            'BEGIN { split(ids, id, "|") } { print prefix id[NR] "," $0 }' >> "$combined_csv"
    fi

    echo
    echo "================ HEAP $heap, RUN $run: THROUGHPUT / LATENCY ================"
    cat "$run_dir/perf_results.csv" 2>/dev/null || echo "(no throughput CSV produced)"
    echo
    echo "================ HEAP $heap, RUN $run: TRACER-OVERHEAD SPLIT ================"
    for jfr in "$run_dir"/perf_*.jfr; do
      [ -e "$jfr" ] || continue
      echo
      echo "---- $(basename "$jfr") ----"
      bash "$script_dir/analyze-jfr.sh" "$jfr" || true
    done
  done
done

if [ ${#heaps[@]} -gt 1 ] || [ "$runs" -gt 1 ]; then
  echo
  echo "================ COMBINED GRID (heap x run x variant) ================"
  cat "$combined_csv" 2>/dev/null || true
fi
if [ "$runs" -gt 1 ] && [ -f "$combined_csv" ]; then
  echo
  echo "================ MEDIAN OF $runs RUNS: THROUGHPUT and KB/req ================"
  # Median per (heap, variant) of every "<endpoint> Throughput" and "<endpoint> KB/req" column,
  # keyed by the stable variant ID (column 3) and showing its reported version (column 4).
  awk -F',' '
    function median(list,   n, v, i, j, t) {
      n = split(list, v, " ")
      for (i = 2; i <= n; i++) { t = v[i] + 0; for (j = i - 1; j >= 1 && v[j] + 0 > t; j--) v[j + 1] = v[j]; v[j + 1] = t }
      return n % 2 ? v[(n + 1) / 2] : (v[n / 2] + v[n / 2 + 1]) / 2
    }
    NR == 1 {
      out = "Heap,Variant,Version"
      for (c = 5; c <= NF; c++) if ($c ~ / Throughput$/ || $c ~ / KB\/req$/) { cols[++nc] = c; out = out "," $c }
      print out; next
    }
    {
      key = $1 "," $3
      if (!(key in seen)) { seen[key] = 1; keys[++nk] = key; version[key] = $4 }
      for (k = 1; k <= nc; k++) vals[key, k] = vals[key, k] " " $(cols[k])
    }
    END {
      for (i = 1; i <= nk; i++) {
        out = keys[i] "," version[keys[i]]
        for (k = 1; k <= nc; k++) out = out "," sprintf("%.2f", median(vals[keys[i], k]))
        print out
      }
    }' "$combined_csv" | tee "$results_dir/median-grid.csv"
fi

echo
log "artifacts saved to: $results_dir"
