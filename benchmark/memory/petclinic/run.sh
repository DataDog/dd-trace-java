#!/usr/bin/env bash
#
# Memory-pressure benchmark for dd-trace-java using spring-petclinic + JMeter.
#
# Runs petclinic at decreasing heap sizes with and without the tracer agent,
# measures stabilized throughput via JMeter, and prints a comparison table.
#
# Usage:
#   ./run.sh [options]
#
# Options:
#   --agent <path>        Path to dd-java-agent.jar (default: auto-detect from build)
#   --petclinic <path>    Path to petclinic jar (default: downloads 3.3.0)
#   --jmeter-home <path>  Path to JMeter installation (default: downloads to ./tools/)
#   --endpoint <path>     URL path to test (default: owners/3)
#   --port <port>         Petclinic server port (default: 8080)
#   --threads <n>         JMeter thread count (default: 8)
#   --warmup <secs>       Warmup duration in seconds (default: 30)
#   --measure <secs>      Measurement duration in seconds (default: 60)
#   --heap-sizes <list>   Comma-separated heap sizes in MB (default: 256,192,128,96,80,64)
#   --java <path>         Path to java binary (default: java)
#   --agent-opts <opts>   Extra JVM opts for agent runs (default: none)
#   --output <dir>        Output directory for results (default: ./results/<timestamp>)
#   --jfr                 Enable JFR recording during measurement phase
#   --skip-baseline       Skip baseline (no-agent) runs
#   --skip-candidate      Skip candidate (with-agent) runs
#   -h, --help            Show this help
#
set -euo pipefail

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"
readonly REPO_ROOT="${SCRIPT_DIR}/../../.."
readonly TOOLS_DIR="${SCRIPT_DIR}/tools"
readonly JMETER_VERSION="5.6.3"
readonly PETCLINIC_COMMIT="7034d17"  # spring-petclinic 3.3.0-SNAPSHOT (Spring Boot 3.3.0)
readonly PETCLINIC_REPO="https://github.com/spring-projects/spring-petclinic.git"

# Defaults
AGENT_JAR=""
PETCLINIC_JAR=""
JMETER_HOME=""
ENDPOINT="owners/3"
PORT=8080
THREADS=8
WARMUP_SECS=30
MEASURE_SECS=60
HEAP_SIZES="256,192,128,96,80,64"
JAVA_BIN="java"
AGENT_OPTS=""
OUTPUT_DIR=""
ENABLE_JFR=false
SKIP_BASELINE=false
SKIP_CANDIDATE=false

# --- Argument parsing ---
while [[ $# -gt 0 ]]; do
  case "$1" in
    --agent)        AGENT_JAR="$2"; shift 2 ;;
    --petclinic)    PETCLINIC_JAR="$2"; shift 2 ;;
    --jmeter-home)  JMETER_HOME="$2"; shift 2 ;;
    --endpoint)     ENDPOINT="$2"; shift 2 ;;
    --port)         PORT="$2"; shift 2 ;;
    --threads)      THREADS="$2"; shift 2 ;;
    --warmup)       WARMUP_SECS="$2"; shift 2 ;;
    --measure)      MEASURE_SECS="$2"; shift 2 ;;
    --heap-sizes)   HEAP_SIZES="$2"; shift 2 ;;
    --java)         JAVA_BIN="$2"; shift 2 ;;
    --agent-opts)   AGENT_OPTS="$2"; shift 2 ;;
    --output)       OUTPUT_DIR="$2"; shift 2 ;;
    --jfr)          ENABLE_JFR=true; shift ;;
    --skip-baseline)  SKIP_BASELINE=true; shift ;;
    --skip-candidate) SKIP_CANDIDATE=true; shift ;;
    -h|--help)
      sed -n '2,/^$/{ s/^#//; s/^ //; p }' "$0"
      exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 1 ;;
  esac
done

TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
OUTPUT_DIR="${OUTPUT_DIR:-${SCRIPT_DIR}/results/${TIMESTAMP}}"
mkdir -p "${OUTPUT_DIR}"

# Resolve JAVA_HOME from the java binary path
resolve_java_home() {
  local java_path="${JAVA_BIN}"
  # Resolve to absolute path if needed
  if [[ "${java_path}" != /* ]]; then
    java_path="$(command -v "${java_path}")"
  fi
  # Follow symlinks
  while [[ -L "${java_path}" ]]; do
    java_path="$(readlink "${java_path}")"
  done
  dirname "$(dirname "${java_path}")"
}
JAVA_HOME_RESOLVED="$(resolve_java_home)"

IFS=',' read -ra HEAPS <<< "${HEAP_SIZES}"

# --- Logging ---
log() { echo "[$(date +%H:%M:%S)] $*"; }
log_section() { echo ""; echo "========================================"; echo "  $*"; echo "========================================"; }

# --- Dependency setup ---

ensure_jmeter() {
  if [[ -n "${JMETER_HOME}" && -x "${JMETER_HOME}/bin/jmeter" ]]; then
    return
  fi
  local jmeter_dir="${TOOLS_DIR}/apache-jmeter-${JMETER_VERSION}"
  if [[ -x "${jmeter_dir}/bin/jmeter" ]]; then
    JMETER_HOME="${jmeter_dir}"
    return
  fi
  log "Downloading JMeter ${JMETER_VERSION}..."
  mkdir -p "${TOOLS_DIR}"
  local archive="${TOOLS_DIR}/jmeter.tgz"
  curl -fSL "https://archive.apache.org/dist/jmeter/binaries/apache-jmeter-${JMETER_VERSION}.tgz" -o "${archive}"
  tar -xzf "${archive}" -C "${TOOLS_DIR}"
  rm -f "${archive}"
  JMETER_HOME="${jmeter_dir}"
  log "JMeter installed at ${JMETER_HOME}"
}

ensure_petclinic() {
  if [[ -n "${PETCLINIC_JAR}" && -f "${PETCLINIC_JAR}" ]]; then
    return
  fi
  local petclinic_dir="${TOOLS_DIR}/spring-petclinic"
  local jar_pattern="${petclinic_dir}/target/spring-petclinic-*.jar"
  # shellcheck disable=SC2086
  if compgen -G ${jar_pattern} > /dev/null 2>&1; then
    PETCLINIC_JAR="$(ls ${jar_pattern} | head -1)"
    return
  fi
  log "Cloning and building spring-petclinic (${PETCLINIC_COMMIT})..."
  mkdir -p "${TOOLS_DIR}"
  if [[ ! -d "${petclinic_dir}" ]]; then
    git clone "${PETCLINIC_REPO}" "${petclinic_dir}"
    git -C "${petclinic_dir}" checkout "${PETCLINIC_COMMIT}"
  fi
  pushd "${petclinic_dir}" > /dev/null
  JAVA_HOME="${JAVA_HOME_RESOLVED}" ./mvnw -q package -DskipTests
  popd > /dev/null
  PETCLINIC_JAR="$(ls ${jar_pattern} | head -1)"
  log "Petclinic built at ${PETCLINIC_JAR}"
}

ensure_agent() {
  if [[ -n "${AGENT_JAR}" && -f "${AGENT_JAR}" ]]; then
    return
  fi
  # Try to find a pre-built agent jar
  local agent_pattern="${REPO_ROOT}/dd-java-agent/build/libs/dd-java-agent-*.jar"
  # shellcheck disable=SC2086
  if compgen -G ${agent_pattern} > /dev/null 2>&1; then
    # Pick the one without -sources or -javadoc suffix
    AGENT_JAR="$(ls ${agent_pattern} | grep -v sources | grep -v javadoc | head -1)"
    log "Using pre-built agent: ${AGENT_JAR}"
    return
  fi
  log "Building dd-java-agent..."
  pushd "${REPO_ROOT}" > /dev/null
  ./gradlew :dd-java-agent:shadowJar -q
  popd > /dev/null
  AGENT_JAR="$(ls ${agent_pattern} | grep -v sources | grep -v javadoc | head -1)"
  log "Agent built at ${AGENT_JAR}"
}

# --- JMeter JMX generation ---
# Generate a minimal JMX inline so we don't depend on an external file.
# N threads, infinite loop, GET /<endpoint>
generate_jmx() {
  local jmx_file="$1"
  cat > "${jmx_file}" <<JMXEOF
<?xml version="1.0" encoding="UTF-8"?>
<jmeterTestPlan version="1.2" properties="5.0" jmeter="5.6.2">
  <hashTree>
    <TestPlan guiclass="TestPlanGui" testclass="TestPlan" testname="Memory Benchmark" enabled="true">
      <boolProp name="TestPlan.functional_mode">false</boolProp>
      <boolProp name="TestPlan.tearDown_on_shutdown">false</boolProp>
      <boolProp name="TestPlan.serialize_threadgroups">false</boolProp>
      <elementProp name="TestPlan.user_defined_variables" elementType="Arguments" guiclass="ArgumentsPanel" testclass="Arguments" testname="User Defined Variables" enabled="true">
        <collectionProp name="Arguments.arguments"/>
      </elementProp>
    </TestPlan>
    <hashTree>
      <ThreadGroup guiclass="ThreadGroupGui" testclass="ThreadGroup" testname="Load" enabled="true">
        <stringProp name="ThreadGroup.on_sample_error">startnextloop</stringProp>
        <elementProp name="ThreadGroup.main_controller" elementType="LoopController" guiclass="LoopControlPanel" testclass="LoopController" testname="Loop Controller" enabled="true">
          <intProp name="LoopController.loops">-1</intProp>
          <boolProp name="LoopController.continue_forever">false</boolProp>
        </elementProp>
        <stringProp name="ThreadGroup.num_threads">${THREADS}</stringProp>
        <stringProp name="ThreadGroup.ramp_time">10</stringProp>
        <boolProp name="ThreadGroup.delayedStart">false</boolProp>
        <boolProp name="ThreadGroup.scheduler">true</boolProp>
        <stringProp name="ThreadGroup.duration">\${__P(duration,90)}</stringProp>
        <stringProp name="ThreadGroup.delay">0</stringProp>
        <boolProp name="ThreadGroup.same_user_on_next_iteration">true</boolProp>
      </ThreadGroup>
      <hashTree>
        <HTTPSamplerProxy guiclass="HttpTestSampleGui" testclass="HTTPSamplerProxy" testname="HTTP Request" enabled="true">
          <boolProp name="HTTPSampler.postBodyRaw">false</boolProp>
          <elementProp name="HTTPsampler.Arguments" elementType="Arguments" guiclass="HTTPArgumentsPanel" testclass="Arguments" testname="User Defined Variables" enabled="true">
            <collectionProp name="Arguments.arguments"/>
          </elementProp>
          <stringProp name="HTTPSampler.domain">localhost</stringProp>
          <stringProp name="HTTPSampler.port">${PORT}</stringProp>
          <stringProp name="HTTPSampler.path">${ENDPOINT}</stringProp>
          <stringProp name="HTTPSampler.method">GET</stringProp>
          <boolProp name="HTTPSampler.follow_redirects">true</boolProp>
          <boolProp name="HTTPSampler.auto_redirects">false</boolProp>
          <boolProp name="HTTPSampler.use_keepalive">true</boolProp>
          <boolProp name="HTTPSampler.DO_MULTIPART_POST">false</boolProp>
          <boolProp name="HTTPSampler.BROWSER_COMPATIBLE_MULTIPART">false</boolProp>
          <boolProp name="HTTPSampler.image_parser">false</boolProp>
          <boolProp name="HTTPSampler.concurrentDwn">false</boolProp>
          <stringProp name="HTTPSampler.concurrentPool">6</stringProp>
          <boolProp name="HTTPSampler.md5">false</boolProp>
          <intProp name="HTTPSampler.ipSourceType">0</intProp>
        </HTTPSamplerProxy>
        <hashTree/>
      </hashTree>
    </hashTree>
  </hashTree>
</jmeterTestPlan>
JMXEOF
}

# --- Server lifecycle ---

wait_for_server() {
  local url="$1"
  local timeout="${2:-120}"
  local start=$SECONDS
  while (( SECONDS - start < timeout )); do
    if curl -fso /dev/null -w "" "${url}" 2>/dev/null; then
      return 0
    fi
    sleep 2
  done
  return 1
}

kill_server() {
  local pid="$1"
  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null || true
    # Wait up to 10s for graceful shutdown
    for _ in $(seq 1 20); do
      kill -0 "$pid" 2>/dev/null || return 0
      sleep 0.5
    done
    kill -9 "$pid" 2>/dev/null || true
  fi
}

# --- Run a single benchmark ---
# Args: variant heap_mb
# Sets global: LAST_THROUGHPUT, LAST_ERROR_RATE, LAST_STATUS
run_single() {
  local variant="$1"  # "baseline" or "candidate"
  local heap_mb="$2"
  local run_dir="${OUTPUT_DIR}/${variant}/${heap_mb}m"
  mkdir -p "${run_dir}"

  LAST_THROUGHPUT="OOM"
  LAST_ERROR_RATE=""
  LAST_STATUS="OOM"

  local total_secs=$(( WARMUP_SECS + MEASURE_SECS ))
  local java_opts="-Xms${heap_mb}m -Xmx${heap_mb}m -Dserver.port=${PORT}"

  if [[ "${variant}" == "candidate" ]]; then
    java_opts="-javaagent:${AGENT_JAR} ${AGENT_OPTS} ${java_opts}"
  fi

  if [[ "${ENABLE_JFR}" == "true" ]]; then
    java_opts="${java_opts} -XX:StartFlightRecording=delay=${WARMUP_SECS}s,duration=${MEASURE_SECS}s,filename=${run_dir}/recording.jfr"
  fi

  log "Starting petclinic [${variant}] -Xmx${heap_mb}m ..."
  ${JAVA_BIN} ${java_opts} -jar "${PETCLINIC_JAR}" \
    > "${run_dir}/petclinic-stdout.log" 2>&1 &
  local server_pid=$!

  # Wait for server to be ready
  if ! wait_for_server "http://localhost:${PORT}/actuator/health" 120; then
    log "  Server failed to start (likely OOM at ${heap_mb}m)"
    kill_server "${server_pid}" 2>/dev/null || true
    return 0
  fi
  log "  Server ready (pid ${server_pid})"

  # Run JMeter
  local jmx_file="${run_dir}/benchmark.jmx"
  local jtl_file="${run_dir}/results.jtl"
  generate_jmx "${jmx_file}"

  log "  Running JMeter: ${WARMUP_SECS}s warmup + ${MEASURE_SECS}s measurement ..."
  JAVA_HOME="${JAVA_HOME_RESOLVED}" "${JMETER_HOME}/bin/jmeter" -n \
    -t "${jmx_file}" \
    -l "${jtl_file}" \
    -Jduration="${total_secs}" \
    > "${run_dir}/jmeter-stdout.log" 2>&1 &
  local jmeter_pid=$!

  # Wait a moment for JMeter to initialize, then verify it's running
  sleep 3
  if ! kill -0 "${jmeter_pid}" 2>/dev/null; then
    log "  ERROR: JMeter failed to start. Check ${run_dir}/jmeter-stdout.log"
    kill_server "${server_pid}"
    LAST_STATUS="JMETER_ERROR"
    LAST_THROUGHPUT="ERR"
    return 0
  fi

  # Monitor for OOM during the run
  local elapsed=0
  while (( elapsed < total_secs + 30 )); do
    if ! kill -0 "${server_pid}" 2>/dev/null; then
      log "  Server died during load (likely OOM)"
      kill "${jmeter_pid}" 2>/dev/null || true
      wait "${jmeter_pid}" 2>/dev/null || true
      return 0
    fi
    if ! kill -0 "${jmeter_pid}" 2>/dev/null; then
      break
    fi
    sleep 5
    elapsed=$(( elapsed + 5 ))
  done

  wait "${jmeter_pid}" 2>/dev/null || true

  # Shut down petclinic
  kill_server "${server_pid}"
  log "  Server stopped"

  # Parse JTL results - only the measurement window (skip warmup)
  if [[ -f "${jtl_file}" ]]; then
    parse_jtl "${jtl_file}" "${WARMUP_SECS}" "${run_dir}"
  fi
}

# --- JTL parsing ---
# JTL CSV format: timeStamp,elapsed,label,responseCode,responseMessage,...
parse_jtl() {
  local jtl_file="$1"
  local warmup_secs="$2"
  local run_dir="$3"

  python3 - "${jtl_file}" "${warmup_secs}" "${run_dir}" <<'PYEOF'
import csv, sys, os

jtl_file = sys.argv[1]
warmup_secs = int(sys.argv[2])
run_dir = sys.argv[3]

timestamps = []
errors = 0
total = 0

with open(jtl_file, 'r') as f:
    reader = csv.DictReader(f)
    for row in reader:
        timestamps.append((int(row['timeStamp']), row.get('success', 'true') == 'true'))

if not timestamps:
    sys.exit(0)

# Sort by timestamp
timestamps.sort(key=lambda x: x[0])
start_ts = timestamps[0][0]
warmup_cutoff = start_ts + (warmup_secs * 1000)

# Filter to measurement window only
measurement = [(ts, ok) for ts, ok in timestamps if ts >= warmup_cutoff]

if len(measurement) < 10:
    sys.exit(0)

total = len(measurement)
errors = sum(1 for _, ok in measurement if not ok)
duration_ms = measurement[-1][0] - measurement[0][0]

if duration_ms <= 0:
    sys.exit(0)

throughput = (total / duration_ms) * 1000  # req/sec
error_rate = (errors / total) * 100

# Write metrics to file for shell to pick up
with open(os.path.join(run_dir, 'metrics.txt'), 'w') as f:
    f.write(f"throughput={throughput:.1f}\n")
    f.write(f"error_rate={error_rate:.2f}\n")
    f.write(f"total_requests={total}\n")
    f.write(f"errors={errors}\n")
    f.write(f"duration_ms={duration_ms}\n")

PYEOF

  if [[ -f "${run_dir}/metrics.txt" ]]; then
    LAST_THROUGHPUT="$(grep '^throughput=' "${run_dir}/metrics.txt" | cut -d= -f2)"
    LAST_ERROR_RATE="$(grep '^error_rate=' "${run_dir}/metrics.txt" | cut -d= -f2)"
    LAST_STATUS="OK"
  fi
}

# --- Results table ---

declare -A RESULTS_BASELINE
declare -A RESULTS_CANDIDATE

print_results() {
  log_section "RESULTS"

  local col_w=14

  # Header
  printf "%-8s" "Heap"
  if [[ "${SKIP_BASELINE}" != "true" ]]; then
    printf "  %-${col_w}s" "Baseline"
  fi
  if [[ "${SKIP_CANDIDATE}" != "true" ]]; then
    printf "  %-${col_w}s" "Candidate"
  fi
  if [[ "${SKIP_BASELINE}" != "true" && "${SKIP_CANDIDATE}" != "true" ]]; then
    printf "  %-${col_w}s" "Delta"
  fi
  echo ""

  printf "%-8s" "------"
  if [[ "${SKIP_BASELINE}" != "true" ]]; then
    printf "  %-${col_w}s" "-----------"
  fi
  if [[ "${SKIP_CANDIDATE}" != "true" ]]; then
    printf "  %-${col_w}s" "-----------"
  fi
  if [[ "${SKIP_BASELINE}" != "true" && "${SKIP_CANDIDATE}" != "true" ]]; then
    printf "  %-${col_w}s" "-----------"
  fi
  echo ""

  for heap in "${HEAPS[@]}"; do
    local b="${RESULTS_BASELINE[${heap}]:-}"
    local c="${RESULTS_CANDIDATE[${heap}]:-}"

    printf "%-8s" "${heap}m"

    if [[ "${SKIP_BASELINE}" != "true" ]]; then
      if [[ "${b}" == "OOM" || -z "${b}" ]]; then
        printf "  %-${col_w}s" "OOM"
      else
        printf "  %-${col_w}s" "${b%.*} req/s"
      fi
    fi

    if [[ "${SKIP_CANDIDATE}" != "true" ]]; then
      if [[ "${c}" == "OOM" || -z "${c}" ]]; then
        printf "  %-${col_w}s" "OOM"
      else
        printf "  %-${col_w}s" "${c%.*} req/s"
      fi
    fi

    if [[ "${SKIP_BASELINE}" != "true" && "${SKIP_CANDIDATE}" != "true" ]]; then
      if [[ "${b}" == "OOM" || "${c}" == "OOM" || -z "${b}" || -z "${c}" ]]; then
        printf "  %-${col_w}s" "N/A"
      else
        python3 -c "
b, c = float('${b}'), float('${c}')
if b > 0:
    delta = ((c - b) / b) * 100
    print(f'  {delta:+.1f}%', end='')
else:
    print('  N/A', end='')
"
      fi
    fi
    echo ""
  done

  echo ""
  echo "Results saved to: ${OUTPUT_DIR}"
  echo ""

  # Also write a CSV summary
  local csv="${OUTPUT_DIR}/summary.csv"
  echo "heap_mb,baseline_rps,candidate_rps,delta_pct" > "${csv}"
  for heap in "${HEAPS[@]}"; do
    local b="${RESULTS_BASELINE[${heap}]:-OOM}"
    local c="${RESULTS_CANDIDATE[${heap}]:-OOM}"
    local delta="N/A"
    if [[ "${b}" != "OOM" && "${c}" != "OOM" && -n "${b}" && -n "${c}" ]]; then
      delta="$(python3 -c "b,c=float('${b}'),float('${c}'); print(f'{((c-b)/b)*100:.2f}') if b>0 else print('N/A')")"
    fi
    echo "${heap},${b},${c},${delta}" >> "${csv}"
  done
}

# --- Main ---

main() {
  log_section "Memory Pressure Benchmark"
  log "Heap sizes: ${HEAP_SIZES}"
  log "Warmup: ${WARMUP_SECS}s  Measure: ${MEASURE_SECS}s  Threads: ${THREADS}"
  log "Endpoint: GET /${ENDPOINT}"
  log "Output: ${OUTPUT_DIR}"

  ensure_jmeter
  ensure_petclinic
  if [[ "${SKIP_CANDIDATE}" != "true" ]]; then
    ensure_agent
  fi

  log "JMeter:    ${JMETER_HOME}"
  log "Petclinic: ${PETCLINIC_JAR}"
  if [[ "${SKIP_CANDIDATE}" != "true" ]]; then
    log "Agent:     ${AGENT_JAR}"
  fi
  log "Java:      $(${JAVA_BIN} -version 2>&1 | head -1)"

  # Save run config
  cat > "${OUTPUT_DIR}/config.txt" <<EOF
timestamp=${TIMESTAMP}
heap_sizes=${HEAP_SIZES}
warmup_secs=${WARMUP_SECS}
measure_secs=${MEASURE_SECS}
threads=${THREADS}
endpoint=${ENDPOINT}
java=$(${JAVA_BIN} -version 2>&1 | head -1)
agent=${AGENT_JAR:-none}
agent_opts=${AGENT_OPTS}
petclinic=${PETCLINIC_JAR}
jfr=${ENABLE_JFR}
EOF

  for heap in "${HEAPS[@]}"; do
    log_section "Heap: ${heap}m"

    if [[ "${SKIP_BASELINE}" != "true" ]]; then
      run_single "baseline" "${heap}"
      RESULTS_BASELINE[${heap}]="${LAST_THROUGHPUT}"
      log "  Baseline: ${LAST_THROUGHPUT} req/s"
    fi

    if [[ "${SKIP_CANDIDATE}" != "true" ]]; then
      run_single "candidate" "${heap}"
      RESULTS_CANDIDATE[${heap}]="${LAST_THROUGHPUT}"
      log "  Candidate: ${LAST_THROUGHPUT} req/s"
    fi
  done

  print_results
}

main "$@"
