#!/usr/bin/env bash
# Benchmarks the regex cache demo app under noagent, agent and agent-cache. See README.md.
# Usage: ./run-bench.sh -a path/to/dd-java-agent.jar [options]
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
redact() { local s="${1//"$REPO_ROOT"/<repo>}"; printf '%s\n' "${s//"$HOME"/<home>}"; }

AGENT_JAR=""
OUT_DIR=""
STARTUP_RUNS=5
WARMUP=15
DURATION=30
CONNECTIONS=8
CONFIGS="noagent,agent,agent-cache"
ENDPOINTS="noregex,split,sanitize,email,mask,compile,dynamic"
SKIP_BUILD=false
HEAP=512m
APP_PORT="${APP_PORT:-18080}"
FAKE_AGENT_PORT="${FAKE_AGENT_PORT:-18126}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    -a) AGENT_JAR="$2"; shift 2 ;;
    -o) OUT_DIR="$2"; shift 2 ;;
    --startup-runs) STARTUP_RUNS="$2"; shift 2 ;;
    --warmup) WARMUP="$2"; shift 2 ;;
    --duration) DURATION="$2"; shift 2 ;;
    --connections) CONNECTIONS="$2"; shift 2 ;;
    --configs) CONFIGS="$2"; shift 2 ;;
    --endpoints) ENDPOINTS="$2"; shift 2 ;;
    --skip-build) SKIP_BUILD=true; shift ;;
    --heap) HEAP="$2"; shift 2 ;;
    -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

java_major() {
  "$1/bin/java" -version 2>&1 | awk -F'"' '/version/ {split($2, v, "."); print (v[1] == "1" ? v[2] : v[1]); exit}'
}

if [[ -z "${BENCH_JAVA_HOME:-}" ]]; then
  if [[ -n "${JAVA_HOME:-}" && "$(java_major "$JAVA_HOME")" -ge 17 ]]; then
    BENCH_JAVA_HOME="$JAVA_HOME"
  elif [[ -x /usr/libexec/java_home ]]; then
    BENCH_JAVA_HOME="$(/usr/libexec/java_home -v 17+)"
  else
    echo "set BENCH_JAVA_HOME to a JDK 17+" >&2; exit 2
  fi
fi
JAVA="$BENCH_JAVA_HOME/bin/java"
if [[ "$(java_major "$BENCH_JAVA_HOME")" -lt 17 ]]; then
  echo "BENCH_JAVA_HOME must be JDK 17+ (Spring Boot 3): $BENCH_JAVA_HOME" >&2; exit 2
fi

if [[ ",$CONFIGS," == *",agent"* ]]; then
  [[ -n "$AGENT_JAR" && -f "$AGENT_JAR" ]] || { echo "-a <dd-java-agent.jar> is required" >&2; exit 2; }
  AGENT_JAR="$(cd "$(dirname "$AGENT_JAR")" && pwd)/$(basename "$AGENT_JAR")"
fi

APP_JAR="$HERE/build/libs/regex-bench-app.jar"
TOOLS_JAR="$HERE/build/libs/regex-bench-tools.jar"
if [[ "$SKIP_BUILD" != true || ! -f "$APP_JAR" || ! -f "$TOOLS_JAR" ]]; then
  echo "== building app and tools"
  "$REPO_ROOT/gradlew" -p "$HERE" -q bootJar loadgenJar
fi

OUT_DIR="${OUT_DIR:-$HERE/results/$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$OUT_DIR/logs"
OUT_DIR="$(cd "$OUT_DIR" && pwd)"
RESULTS="$OUT_DIR/results.tsv"
: > "$RESULTS"

port_in_use() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
for p in "$APP_PORT" "$FAKE_AGENT_PORT"; do
  if port_in_use "$p"; then echo "port $p is already in use" >&2; exit 1; fi
done

PIDS=()
cleanup() {
  for pid in "${PIDS[@]:-}"; do
    [[ -n "$pid" ]] && kill "$pid" 2>/dev/null || true
  done
  wait 2>/dev/null || true
}
trap cleanup EXIT

# JVM flags shared by every config so the only difference is the agent / cache flag.
COMMON_JVM=(-Xms"$HEAP" -Xmx"$HEAP" -XX:+UseG1GC)

# Agent baseline ("agent installed"): tracing on, so Spring MVC / Tomcat server spans are created,
# serialized and sent to a local fake agent that just returns 200. Everything that adds background
# noise unrelated to tracing is off. agent and agent-cache differ ONLY in the regex cache flag.
AGENT_COMMON=(
  "-javaagent:$AGENT_JAR"
  -Ddd.service=regex-bench -Ddd.env=bench -Ddd.version=1
  -Ddd.trace.enabled=true
  "-Ddd.trace.agent.url=http://127.0.0.1:$FAKE_AGENT_PORT"
  -Ddd.remote_config.enabled=false
  -Ddd.telemetry.enabled=false
  -Ddd.profiling.enabled=false
  -Ddd.appsec.enabled=false
  -Ddd.iast.enabled=false
  -Ddd.dynamic.instrumentation.enabled=false
  -Ddd.data.streams.enabled=false
  -Ddd.jmxfetch.enabled=false
  -Ddd.trace.health.metrics.enabled=false
  -Ddd.trace.stats.computation.enabled=false
  -Ddd.crashtracking.enabled=false
  -Ddd.trace.startup.logs=false
)

jvm_args_for() {
  case "$1" in
    noagent) printf '%s\n' "${COMMON_JVM[@]}" ;;
    agent) printf '%s\n' "${COMMON_JVM[@]}" "${AGENT_COMMON[@]}" -Ddd.trace.experimental.regex.cache.enabled=false ;;
    agent-cache) printf '%s\n' "${COMMON_JVM[@]}" "${AGENT_COMMON[@]}" -Ddd.trace.experimental.regex.cache.enabled=true ;;
    *) echo "unknown config $1" >&2; exit 2 ;;
  esac
}

TOOLS=("$JAVA" -cp "$TOOLS_JAR")

if [[ ",$CONFIGS," == *",agent"* ]]; then
  echo "== starting fake trace agent on $FAKE_AGENT_PORT"
  "${TOOLS[@]}" com.example.regexbench.tools.FakeAgent "$FAKE_AGENT_PORT" > "$OUT_DIR/logs/fake-agent.log" 2>&1 &
  PIDS+=($!)
  for _ in $(seq 1 100); do port_in_use "$FAKE_AGENT_PORT" && break; sleep 0.1; done
fi

IFS=',' read -r -a CONFIG_LIST <<< "$CONFIGS"
for config in "${CONFIG_LIST[@]}"; do
  JVM_ARGS=()
  while IFS= read -r line; do JVM_ARGS+=("$line"); done < <(jvm_args_for "$config")
  APP_CMD=("$JAVA" "${JVM_ARGS[@]}" "-DPORT=$APP_PORT" -jar "$APP_JAR")
  for arg in "${APP_CMD[@]}"; do redact "$arg"; done > "$OUT_DIR/logs/$config.cmdline"

  echo "== [$config] startup x$STARTUP_RUNS"
  "${TOOLS[@]}" com.example.regexbench.tools.StartupProbe \
    --url "http://127.0.0.1:$APP_PORT/noregex" --config "$config" --runs "$STARTUP_RUNS" \
    --timeout 180 --log "$OUT_DIR/logs/$config-startup.log" --out "$RESULTS" -- "${APP_CMD[@]}"

  echo "== [$config] load: warmup ${WARMUP}s + ${DURATION}s per endpoint, $CONNECTIONS connections"
  "${APP_CMD[@]}" > "$OUT_DIR/logs/$config-load.log" 2>&1 &
  APP_PID=$!
  PIDS+=("$APP_PID")
  "${TOOLS[@]}" com.example.regexbench.tools.LoadGen wait --base "http://127.0.0.1:$APP_PORT" --timeout 180
  "${TOOLS[@]}" com.example.regexbench.tools.LoadGen run --base "http://127.0.0.1:$APP_PORT" \
    --config "$config" --endpoints "$ENDPOINTS" --warmup "$WARMUP" --duration "$DURATION" \
    --connections "$CONNECTIONS" --out "$RESULTS"
  if [[ "$config" == agent* ]]; then
    sleep 2 # let the tracer flush
    PAYLOADS="$("${TOOLS[@]}" com.example.regexbench.tools.LoadGen get --url "http://127.0.0.1:$FAKE_AGENT_PORT/bench/trace-payloads")"
    echo "  fake agent has received $PAYLOADS trace payloads so far"
    [[ "$PAYLOADS" -gt 0 ]] || echo "  WARNING: tracer sent no traces; the agent baseline is not exercising the trace pipeline" >&2
  fi
  kill "$APP_PID" 2>/dev/null || true
  wait "$APP_PID" 2>/dev/null || true
  for _ in $(seq 1 100); do port_in_use "$APP_PORT" || break; sleep 0.1; done
done

META="date=$(date -u +%Y-%m-%dT%H:%M:%SZ);host=$(uname -srm);cpus=$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo ?)"
META+=";java=$("$JAVA" -version 2>&1 | head -1 | tr -d '"')"
AGENT_JAR_LABEL="${AGENT_JAR:-none}"
META+=";agentJar=$(redact "$AGENT_JAR_LABEL");agentCommit=$(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || echo ?)"
META+=";startupRuns=$STARTUP_RUNS;warmupS=$WARMUP;durationS=$DURATION;connections=$CONNECTIONS;heap=$HEAP"
"${TOOLS[@]}" com.example.regexbench.tools.Report "$RESULTS" "$OUT_DIR" "$META"

redact "== done: $OUT_DIR/summary.md"
cat "$OUT_DIR/summary.md"
