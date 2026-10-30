#!/usr/bin/env bash
# Runs the crypto demo app under noagent, agent and agent-accp (and optionally noagent-accp) and
# compares startup, responses and throughput. See README.md for options; run via ./in-docker.sh.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/bench-env.sh"

AGENT_JAR=""
OUT_DIR=""
STARTUP_RUNS=5
WARMUP=15
DURATION=30
CONNECTIONS=8
CONFIGS="noagent,agent,agent-accp"
ENDPOINTS="nocrypto,jwt,aesgcm,tls"
CERT=rsa
HEAP=512m
APP_PORT="${APP_PORT:-18080}"
HTTPS_PORT="${HTTPS_PORT:-18443}"
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
    --cert) CERT="$2"; shift 2 ;;
    --heap) HEAP="$2"; shift 2 ;;
    -h|--help) echo "usage: $0 -a AGENT_JAR [-o DIR] [--startup-runs N] [--warmup S] [--duration S] [--connections N] [--configs LIST] [--endpoints LIST] [--cert rsa|ec] [--heap SIZE]"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

if [[ ",$CONFIGS," == *",agent"* ]]; then
  [[ -n "$AGENT_JAR" && -f "$AGENT_JAR" ]] || { echo "-a <dd-java-agent.jar> is required" >&2; exit 2; }
  AGENT_JAR="$(cd "$(dirname "$AGENT_JAR")" && pwd)/$(basename "$AGENT_JAR")"
fi

APP_LIB="$HERE/build/app/lib"
TOOLS_JAR="$HERE/build/libs/crypto-bench-tools.jar"
require_built "$APP_LIB/crypto-bench-app.jar" "$TOOLS_JAR" "$ACCP_JAR"

OUT_DIR="${OUT_DIR:-$HERE/results/$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$OUT_DIR/logs"
OUT_DIR="$(cd "$OUT_DIR" && pwd)"
RESULTS="$OUT_DIR/results.tsv"
: > "$RESULTS"

port_in_use() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
for p in "$APP_PORT" "$HTTPS_PORT" "$FAKE_AGENT_PORT"; do
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

# Same JVM flags and class path for every config (flat class path, like a Jib image, so the
# agent can load ACCP through the system class loader).
COMMON_JVM=(-Xms"$HEAP" -Xmx"$HEAP" -XX:+UseG1GC "-Dbench.https.cert=$CERT")
CLASSPATH="$APP_LIB/*:$ACCP_JAR"

# Agent baseline ("agent installed"): tracing on, so Spring MVC / Tomcat server spans are created,
# serialized and sent to a local fake agent that just returns 200. Everything that adds background
# noise unrelated to tracing is off. agent and agent-accp differ ONLY in the crypto provider flag.
AGENT_COMMON=(
  "-javaagent:$AGENT_JAR"
  -Ddd.service=crypto-bench -Ddd.env=bench -Ddd.version=1
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
  -Ddd.trace.experimental.crypto.provider.start-delay=0
)

jvm_args_for() {
  case "$1" in
    noagent) printf '%s\n' "${COMMON_JVM[@]}" ;;
    noagent-accp) printf '%s\n' "${COMMON_JVM[@]}" -Dbench.install.accp=true ;;
    agent) printf '%s\n' "${COMMON_JVM[@]}" "${AGENT_COMMON[@]}" -Ddd.trace.experimental.crypto.provider.enabled=false ;;
    agent-accp) printf '%s\n' "${COMMON_JVM[@]}" "${AGENT_COMMON[@]}" -Ddd.trace.experimental.crypto.provider.enabled=true ;;
    *) echo "unknown config $1" >&2; exit 2 ;;
  esac
}

TOOLS=("$JAVA" -cp "$TOOLS_JAR")
BASES=(--base "http://127.0.0.1:$APP_PORT" --https-base "https://127.0.0.1:$HTTPS_PORT")

if [[ ",$CONFIGS," == *",agent"* ]]; then
  echo "== starting fake trace agent on $FAKE_AGENT_PORT"
  "${TOOLS[@]}" com.example.cryptobench.tools.FakeAgent "$FAKE_AGENT_PORT" > "$OUT_DIR/logs/fake-agent.log" 2>&1 &
  PIDS+=($!)
  for _ in $(seq 1 100); do port_in_use "$FAKE_AGENT_PORT" && break; sleep 0.1; done
fi

IFS=',' read -r -a CONFIG_LIST <<< "$CONFIGS"
for config in "${CONFIG_LIST[@]}"; do
  JVM_ARGS=()
  while IFS= read -r line; do JVM_ARGS+=("$line"); done < <(jvm_args_for "$config")
  APP_CMD=("$JAVA" "${JVM_ARGS[@]}" "-DPORT=$APP_PORT" "-DHTTPS_PORT=$HTTPS_PORT" -cp "$CLASSPATH"
    com.example.cryptobench.CryptoBenchApplication)
  for arg in "${APP_CMD[@]}"; do redact "$arg"; done > "$OUT_DIR/logs/$config.cmdline"

  echo "== [$config] startup x$STARTUP_RUNS"
  "${TOOLS[@]}" com.example.cryptobench.tools.StartupProbe \
    --url "http://127.0.0.1:$APP_PORT/nocrypto" --config "$config" --runs "$STARTUP_RUNS" \
    --timeout 180 --log "$OUT_DIR/logs/$config-startup.log" --out "$RESULTS" -- "${APP_CMD[@]}"

  echo "== [$config] verify + load: warmup ${WARMUP}s + ${DURATION}s per endpoint, $CONNECTIONS connections"
  "${APP_CMD[@]}" > "$OUT_DIR/logs/$config-load.log" 2>&1 &
  APP_PID=$!
  PIDS+=("$APP_PID")
  "${TOOLS[@]}" com.example.cryptobench.tools.LoadGen wait "${BASES[@]}" --timeout 180
  "${TOOLS[@]}" com.example.cryptobench.tools.LoadGen verify "${BASES[@]}" --config "$config" --out "$RESULTS"
  "${TOOLS[@]}" com.example.cryptobench.tools.LoadGen run "${BASES[@]}" \
    --config "$config" --endpoints "$ENDPOINTS" --warmup "$WARMUP" --duration "$DURATION" \
    --connections "$CONNECTIONS" --out "$RESULTS"
  if [[ "$config" == agent* ]]; then
    sleep 2 # let the tracer flush
    PAYLOADS="$("${TOOLS[@]}" com.example.cryptobench.tools.LoadGen get --url "http://127.0.0.1:$FAKE_AGENT_PORT/bench/trace-payloads")"
    echo "  fake agent has received $PAYLOADS trace payloads so far"
    [[ "$PAYLOADS" -gt 0 ]] || echo "  WARNING: tracer sent no traces; the agent baseline is not exercising the trace pipeline" >&2
  fi
  kill "$APP_PID" 2>/dev/null || true
  wait "$APP_PID" 2>/dev/null || true
  for _ in $(seq 1 100); do port_in_use "$APP_PORT" || port_in_use "$HTTPS_PORT" || break; sleep 0.1; done
done

META="date=$(date -u +%Y-%m-%dT%H:%M:%SZ);host=$(uname -srm);cpus=$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo ?)"
META+=";java=$("$JAVA" -version 2>&1 | head -1 | tr -d '"')"
META+=";accp=$(basename "$ACCP_JAR");httpsCert=$CERT"
AGENT_JAR_LABEL="${AGENT_JAR:-none}"
AGENT_JAR_LABEL="$(redact "$AGENT_JAR_LABEL")"
META+=";agentJar=$AGENT_JAR_LABEL;agentCommit=${BENCH_AGENT_COMMIT:-$(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || echo ?)}"
META+=";startupRuns=$STARTUP_RUNS;warmupS=$WARMUP;durationS=$DURATION;connections=$CONNECTIONS;heap=$HEAP"
STATUS=0
"${TOOLS[@]}" com.example.cryptobench.tools.Report "$RESULTS" "$OUT_DIR" "$META" || STATUS=$?

redact "== done: $OUT_DIR/summary.md"
cat "$OUT_DIR/summary.md"
exit "$STATUS"
