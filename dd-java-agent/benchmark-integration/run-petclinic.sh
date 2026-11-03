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
    ( cd "$repo_root" && ./gradlew -q :dd-java-agent:shadowJar >&2 )
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
for v in "${variants[@]}"; do
  resolved+=("$(resolve_variant "$v")")
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

# Heap-sweep dimension: run the full variant sweep once per heap size. PETCLINIC_HEAPS is a
# space-separated list (e.g. "64m 256m 512m"); default is a single 256m (no heap sweep).
# NOTE: total runtime is heaps x variants -- a heap list multiplies the wall clock.
heaps=(${PETCLINIC_HEAPS:-256m})

# Honor a pre-set PERF_SETTINGS (e.g. a smoke-test rc); otherwise use the PetClinic settings.
settings="${PERF_SETTINGS:-$script_dir/perf-test-petclinic-settings.rc}"

results_dir="$script_dir/build/petclinic-results/results-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$results_dir"
combined_csv="$results_dir/throughput-grid.csv"

log "sweeping ${#resolved[@]} variant(s) x ${#heaps[@]} heap(s) [${heaps[*]}] x ${PETCLINIC_REPEATS:-1} repeat(s), JFR enabled, settings=$settings"
for heap in "${heaps[@]}"; do
  heap_dir="$results_dir/heap-$heap"
  mkdir -p "$heap_dir"
  rm -f /tmp/perf_*.jfr /tmp/perf_gc_*.log /tmp/perf_results.csv
  log "=== HEAP $heap ==="
  (
    cd "$script_dir"
    PERF_JFR=1 PERF_HEAP="$heap" PERF_REPEATS="${PETCLINIC_REPEATS:-1}" PERF_SETTINGS="$settings" \
      bash ./run-perf-test.sh jar "$petclinic_jar" "${resolved[@]}"
  )
  cp -f /tmp/perf_results.csv "$heap_dir/" 2>/dev/null || true
  cp -f /tmp/perf_*.jfr /tmp/perf_gc_*.log "$heap_dir/" 2>/dev/null || true

  # Fold this heap's throughput into the combined grid CSV with a leading Heap column.
  if [ -f /tmp/perf_results.csv ]; then
    [ -f "$combined_csv" ] || echo "Heap,$(head -1 /tmp/perf_results.csv)" > "$combined_csv"
    tail -n +2 /tmp/perf_results.csv | sed "s/^/$heap,/" >> "$combined_csv"
  fi

  echo
  echo "================ HEAP $heap: THROUGHPUT / LATENCY ================"
  cat "$heap_dir/perf_results.csv" 2>/dev/null || echo "(no throughput CSV produced)"
  echo
  echo "================ HEAP $heap: TRACER-OVERHEAD SPLIT ================"
  for jfr in "$heap_dir"/perf_*.jfr; do
    [ -e "$jfr" ] || continue
    echo
    echo "---- $(basename "$jfr") ----"
    bash "$script_dir/analyze-jfr.sh" "$jfr" || true
  done
done

if [ ${#heaps[@]} -gt 1 ]; then
  echo
  echo "================ COMBINED THROUGHPUT GRID (heap x version) ================"
  cat "$combined_csv" 2>/dev/null || true
fi

echo
log "artifacts saved to: $results_dir"
