#!/usr/bin/env bash
#
# Record a JFR of PetClinic under fixed-rate load, for one agent variant: warm up at saturation, then
# start a recording (lock events at a 0 ms threshold) while vegeta holds a fixed request rate.
#
# Usage:
#   ./profile-run.sh <label> [agent jar]      (no jar = NoAgent)
#
# A jar named opentelemetry-javaagent* is run as the OpenTelemetry agent, exporting OTLP/http to the
# same Agent stub the Datadog agent sends to. Output: build/profiles/<label>.jfr, plus the warmup
# throughput and the fixed-rate latency report on stdout. Analyze with waits.py and gc-cpu.py.
#
# Environment:
#   PROFILE_URL          endpoint to load (default http://localhost:8080/owners/1)
#   PROFILE_WARMUP       warmup seconds at saturation (default 90; a cold JVM going straight to one
#                        endpoint needs ~45s before the JIT catches up)
#   PROFILE_RATE         fixed request rate during the recording (default 8000)
#   PROFILE_SECONDS      recording length (default 40; the fixed-rate phase runs 5s longer)
#   SAMPLED_FRACTION     share of requests whose upstream decision is keep (default 1)
#   EXTRA_ARGS           extra server JVM options, e.g. -Ddd.trace.agent.protocol.version=0.5
#   PROFILE_STUB_PORT    Agent stub port (default 8147); started here if nothing answers on it
#
# Requires: a JDK 17+ (jcmd, jfr), wrk, vegeta, curl, python3.

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
bench_dir="$(cd "$script_dir/.." && pwd)"
out_dir="$bench_dir/build/profiles"
mkdir -p "$out_dir"

label="${1:?usage: profile-run.sh <label> [agent jar]}"
jar="${2:-}"
url="${PROFILE_URL:-http://localhost:8080/owners/1}"
warmup="${PROFILE_WARMUP:-90}"
rate="${PROFILE_RATE:-8000}"
seconds="${PROFILE_SECONDS:-40}"
fraction="${SAMPLED_FRACTION:-1}"
port="${PROFILE_STUB_PORT:-8147}"

java_home="${BENCH_JAVA_HOME:-${JAVA_17_HOME:-${JAVA_HOME:-}}}"
[ -x "$java_home/bin/jcmd" ] || { echo "need a JDK 17+ in BENCH_JAVA_HOME, JAVA_17_HOME or JAVA_HOME" >&2; exit 1; }
export PATH="$java_home/bin:$PATH"

# JFR settings: the profile template with monitor-enter, monitor-wait and park events at 0 ms.
settings="$out_dir/locks.jfc"
[ -f "$settings" ] || jfr configure --input profile +jdk.JavaMonitorEnter#threshold=0ms \
  +jdk.ThreadPark#threshold=0ms +jdk.JavaMonitorWait#threshold=0ms --output "$settings" > /dev/null

stub_pid=""
if ! curl -sf -o /dev/null "http://127.0.0.1:$port/info"; then
  java "$bench_dir/agent-stub/AgentStub.java" "$port" "$bench_dir/agent-stub/agent-info.json" \
    > "$out_dir/agent-stub.log" 2>&1 &
  stub_pid=$!
  for _ in $(seq 30); do curl -sf -o /dev/null "http://127.0.0.1:$port/info" && break; sleep 1; done
fi
cleanup() { [ -n "${server_pid:-}" ] && kill "$server_pid" 2>/dev/null; [ -n "$stub_pid" ] && kill "$stub_pid" 2>/dev/null; true; }
trap cleanup EXIT

agent_args=()
if [[ "$(basename "${jar:-none}")" == opentelemetry-javaagent* ]]; then
  agent_args=(-javaagent:"$jar" -Dotel.javaagent.logging=none -Dotel.service.name=perf-test-app
    -Dotel.traces.exporter=otlp -Dotel.exporter.otlp.protocol=http/protobuf
    "-Dotel.exporter.otlp.endpoint=http://127.0.0.1:$port" -Dotel.metrics.exporter=none -Dotel.logs.exporter=none)
elif [ -n "$jar" ]; then
  agent_args=(-javaagent:"$jar" -Ddatadog.slf4j.simpleLogger.defaultLogLevel=off -Ddd.writer.type=DDAgentWriter
    "-Ddd.trace.agent.port=$port" -Ddd.service.name=perf-test-app)
fi
read -r -a extra_args <<< "${EXTRA_ARGS:-}"

petclinic_jar="$(bash "$bench_dir/fetch-petclinic.sh")"
SPRING_WEB_RESOURCES_CHAIN_ENABLED=false java "${agent_args[@]}" "${extra_args[@]}" -Xms2g -Xmx2g \
  -jar "$petclinic_jar" > "$out_dir/server-$label.log" 2>&1 &
launch_pid=$!
for _ in $(seq 180); do curl -sf -o /dev/null "$url" && break; sleep 1; done
server_pid=$(lsof -nP -iTCP:8080 -sTCP:LISTEN -t)

UPSTREAM_SAMPLED_FRACTION="$fraction" wrk -c 64 -t 4 -d "${warmup}s" -s "$bench_dir/wrk-upstream-headers.lua" "$url" \
  | grep Requests/sec | sed "s/^/[$label] warmup /"

jcmd "$server_pid" JFR.start name=profile settings="$settings" duration="${seconds}s" \
  filename="$out_dir/$label.jfr" > /dev/null
vegeta_url="${url/localhost/127.0.0.1}"
python3 "$bench_dir/vegeta-targets.py" "$vegeta_url" --upstream-headers --sampled-fraction "$fraction" \
  > "$out_dir/targets.json"
vegeta attack -format=json -targets="$out_dir/targets.json" -rate="$rate" -duration="$((seconds + 5))s" \
  -max-connections=256 | vegeta report | sed -n '3p;6p' | sed "s/^/[$label] /"
sleep 3
kill "$server_pid"; wait "$launch_pid" 2>/dev/null || true
server_pid=""
echo "[$label] recording: $out_dir/$label.jfr"
