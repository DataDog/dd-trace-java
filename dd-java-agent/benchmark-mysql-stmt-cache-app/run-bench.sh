#!/usr/bin/env bash
# MySQL Connector/J statement cache POC benchmark. See README.md or --help.
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: ./run-bench.sh -a path/to/dd-java-agent.jar [options]
  -a JAR               dd-java-agent jar (required for --mode app/all unless --configs is noagent)
  -o DIR               output dir (default: ./results/<timestamp>)
  --mode M             app | micro | all (default app)
  --startup-runs N     startup runs per config (default 5)
  --warmup S           warmup seconds per endpoint (default 15)
  --duration S         measured seconds per endpoint (default 30)
  --connections N      concurrent keep-alive connections (default 8)
  --configs LIST       comma-separated subset of noagent,agent,agent-stmtcache (default: all)
  --endpoints LIST     default: nodb,db
  --micro-rounds N     measured rounds per microbenchmark cell (default 5)
  --micro-seconds S    seconds per microbenchmark cell per round (default 3)
  --skip-build         reuse build/libs jars
  --heap SIZE          -Xms/-Xmx for the app (default 512m)
  --mysql-port P       default 13306
  --no-container       don't manage a MySQL container
  --keep-mysql         leave the container running at the end
Env: BENCH_JAVA_HOME  JDK 17+ for the app and tools (default: JAVA_HOME if 17+, else
                      /usr/libexec/java_home -v 17+ on macOS)
     APP_PORT (18080), FAKE_AGENT_PORT (18126), MYSQL_IMAGE (mysql:8.0)

--mode app (default): runs the demo app under three configs, one after another:
  noagent          no -javaagent
  agent            dd-java-agent, stmt cache flag explicitly false
  agent-stmtcache  dd-java-agent, -Ddd.trace.experimental.mysql.stmt.cache.enabled=true
For each: startup to first successful request (median of N runs), then per-endpoint warmup +
fixed-duration closed-loop load. Writes results.tsv, summary.json and summary.md to the out dir.

--mode micro: library-level microbenchmark, no agent: Connector/J alone, cache off vs on.
  Writes micro.tsv and micro.md to the out dir.
--mode all: both.

MySQL: by default a mysql:8.0 container named stmtcache-bench-mysql is started on 127.0.0.1:13306
(reused if it exists) and removed at the end unless --keep-mysql. --no-container uses whatever
listens on --mysql-port (needs database/user/password "bench").
USAGE
}

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
redact() { local s="${1//"$REPO_ROOT"/<repo>}"; printf '%s\n' "${s//"$HOME"/<home>}"; }

AGENT_JAR=""
OUT_DIR=""
MODE=app
STARTUP_RUNS=5
WARMUP=15
DURATION=30
CONNECTIONS=8
CONFIGS="noagent,agent,agent-stmtcache"
ENDPOINTS="nodb,db"
MICRO_ROUNDS=5
MICRO_SECONDS=3
SKIP_BUILD=false
HEAP=512m
MYSQL_PORT=13306
MANAGE_CONTAINER=true
KEEP_MYSQL=false
APP_PORT="${APP_PORT:-18080}"
FAKE_AGENT_PORT="${FAKE_AGENT_PORT:-18126}"
MYSQL_IMAGE="${MYSQL_IMAGE:-mysql:8.0}"
CONTAINER=stmtcache-bench-mysql

while [[ $# -gt 0 ]]; do
  case "$1" in
    -a) AGENT_JAR="$2"; shift 2 ;;
    -o) OUT_DIR="$2"; shift 2 ;;
    --mode) MODE="$2"; shift 2 ;;
    --startup-runs) STARTUP_RUNS="$2"; shift 2 ;;
    --warmup) WARMUP="$2"; shift 2 ;;
    --duration) DURATION="$2"; shift 2 ;;
    --connections) CONNECTIONS="$2"; shift 2 ;;
    --configs) CONFIGS="$2"; shift 2 ;;
    --endpoints) ENDPOINTS="$2"; shift 2 ;;
    --micro-rounds) MICRO_ROUNDS="$2"; shift 2 ;;
    --micro-seconds) MICRO_SECONDS="$2"; shift 2 ;;
    --skip-build) SKIP_BUILD=true; shift ;;
    --heap) HEAP="$2"; shift 2 ;;
    --mysql-port) MYSQL_PORT="$2"; shift 2 ;;
    --no-container) MANAGE_CONTAINER=false; shift ;;
    --keep-mysql) KEEP_MYSQL=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
case "$MODE" in app|micro|all) ;; *) echo "--mode must be app, micro or all" >&2; exit 2 ;; esac
RUN_APP=false; RUN_MICRO=false
[[ "$MODE" == app || "$MODE" == all ]] && RUN_APP=true
[[ "$MODE" == micro || "$MODE" == all ]] && RUN_MICRO=true

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

if [[ "$RUN_APP" == true && ",$CONFIGS," == *",agent"* ]]; then
  [[ -n "$AGENT_JAR" && -f "$AGENT_JAR" ]] || { echo "-a <dd-java-agent.jar> is required" >&2; exit 2; }
  AGENT_JAR="$(cd "$(dirname "$AGENT_JAR")" && pwd)/$(basename "$AGENT_JAR")"
fi

APP_JAR="$HERE/build/libs/stmtcache-bench-app.jar"
TOOLS_JAR="$HERE/build/libs/stmtcache-bench-tools.jar"
MICRO_JAR="$HERE/build/libs/stmtcache-micro.jar"
if [[ "$SKIP_BUILD" != true || ! -f "$APP_JAR" || ! -f "$TOOLS_JAR" || ! -f "$MICRO_JAR" ]]; then
  echo "== building app, tools and microbenchmark"
  "$REPO_ROOT/gradlew" -p "$HERE" -q bootJar loadgenJar microJar
fi

OUT_DIR="${OUT_DIR:-$HERE/results/$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$OUT_DIR/logs"
OUT_DIR="$(cd "$OUT_DIR" && pwd)"
RESULTS="$OUT_DIR/results.tsv"

port_in_use() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
if [[ "$RUN_APP" == true ]]; then
  for p in "$APP_PORT" "$FAKE_AGENT_PORT"; do
    if port_in_use "$p"; then echo "port $p is already in use" >&2; exit 1; fi
  done
fi

PIDS=()
STARTED_CONTAINER=false
cleanup() {
  for pid in "${PIDS[@]:-}"; do
    [[ -n "$pid" ]] && kill "$pid" 2>/dev/null || true
  done
  wait 2>/dev/null || true
  if [[ "$STARTED_CONTAINER" == true && "$KEEP_MYSQL" != true ]]; then
    docker rm -f "$CONTAINER" > /dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

if [[ "$MANAGE_CONTAINER" == true ]]; then
  if docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
    echo "== reusing running container $CONTAINER"
  elif docker ps -a --format '{{.Names}}' | grep -qx "$CONTAINER"; then
    echo "== starting existing container $CONTAINER"
    docker start "$CONTAINER" > /dev/null
    STARTED_CONTAINER=true
  else
    echo "== starting $MYSQL_IMAGE as $CONTAINER on 127.0.0.1:$MYSQL_PORT"
    # tmpfs datadir: no disk I/O noise; the data is reseeded on every run anyway
    docker run -d --name "$CONTAINER" -p "127.0.0.1:$MYSQL_PORT:3306" --tmpfs /var/lib/mysql \
      -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=bench -e MYSQL_USER=bench -e MYSQL_PASSWORD=bench \
      "$MYSQL_IMAGE" > /dev/null
    STARTED_CONTAINER=true
  fi
fi

echo "== seeding demo schema (no agent)"
for i in $(seq 1 90); do
  if "$JAVA" -Dbench.seed=true "-DMYSQL_PORT=$MYSQL_PORT" -jar "$APP_JAR" > "$OUT_DIR/logs/seed.log" 2>&1; then
    tail -1 "$OUT_DIR/logs/seed.log"; break
  fi
  [[ "$i" -eq 90 ]] && { echo "MySQL not ready / seeding failed; see $OUT_DIR/logs/seed.log" >&2; exit 1; }
  sleep 2
done
MYSQL_VERSION="$(docker exec "$CONTAINER" mysqld --version 2>/dev/null | awk '{print $3}' || echo '?')"

if [[ "$RUN_MICRO" == true ]]; then
  echo "== microbenchmark (no agent): $MICRO_ROUNDS rounds x ${MICRO_SECONDS}s per cell"
  "$JAVA" -Xms512m -Xmx512m -XX:+UseG1GC -cp "$MICRO_JAR" com.example.stmtcachebench.micro.StmtCacheMicroBench \
    --url "jdbc:mysql://127.0.0.1:$MYSQL_PORT/bench" --user bench --password bench \
    --rounds "$MICRO_ROUNDS" --seconds "$MICRO_SECONDS" --out "$OUT_DIR" | tee "$OUT_DIR/logs/micro.log"
fi

if [[ "$RUN_APP" != true ]]; then
  redact "== done: $OUT_DIR"
  exit 0
fi

: > "$RESULTS"

# JVM flags shared by every config so the only difference is the agent / cache flag.
COMMON_JVM=(-Xms"$HEAP" -Xmx"$HEAP" -XX:+UseG1GC "-DMYSQL_PORT=$MYSQL_PORT")

AGENT_COMMON=(
  "-javaagent:$AGENT_JAR"
  -Ddd.service=stmtcache-bench -Ddd.env=bench -Ddd.version=1
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
    agent) printf '%s\n' "${COMMON_JVM[@]}" "${AGENT_COMMON[@]}" -Ddd.trace.experimental.mysql.stmt.cache.enabled=false ;;
    agent-stmtcache) printf '%s\n' "${COMMON_JVM[@]}" "${AGENT_COMMON[@]}" -Ddd.trace.experimental.mysql.stmt.cache.enabled=true ;;
    *) echo "unknown config $1" >&2; exit 2 ;;
  esac
}

TOOLS=("$JAVA" -cp "$TOOLS_JAR")

if [[ ",$CONFIGS," == *",agent"* ]]; then
  echo "== starting fake trace agent on $FAKE_AGENT_PORT"
  "${TOOLS[@]}" com.example.stmtcachebench.tools.FakeAgent "$FAKE_AGENT_PORT" > "$OUT_DIR/logs/fake-agent.log" 2>&1 &
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
  "${TOOLS[@]}" com.example.stmtcachebench.tools.StartupProbe \
    --url "http://127.0.0.1:$APP_PORT/nodb" --config "$config" --runs "$STARTUP_RUNS" \
    --timeout 180 --log "$OUT_DIR/logs/$config-startup.log" --out "$RESULTS" -- "${APP_CMD[@]}"

  echo "== [$config] load: warmup ${WARMUP}s + ${DURATION}s per endpoint, $CONNECTIONS connections"
  "${APP_CMD[@]}" > "$OUT_DIR/logs/$config-load.log" 2>&1 &
  APP_PID=$!
  PIDS+=("$APP_PID")
  "${TOOLS[@]}" com.example.stmtcachebench.tools.LoadGen wait --base "http://127.0.0.1:$APP_PORT" --timeout 180
  "${TOOLS[@]}" com.example.stmtcachebench.tools.LoadGen run --base "http://127.0.0.1:$APP_PORT" \
    --config "$config" --endpoints "$ENDPOINTS" --warmup "$WARMUP" --duration "$DURATION" \
    --connections "$CONNECTIONS" --out "$RESULTS"
  STATS="$("${TOOLS[@]}" com.example.stmtcachebench.tools.LoadGen get --url "http://127.0.0.1:$APP_PORT/admin/stats")"
  echo "  effective statement cache: $(echo "$STATS" | grep -E '^cache(PrepStmts|dStatements)=' | tr '\n' ' ')"
  if [[ "$config" == agent* ]]; then
    sleep 2 # let the tracer flush
    PAYLOADS="$("${TOOLS[@]}" com.example.stmtcachebench.tools.LoadGen get --url "http://127.0.0.1:$FAKE_AGENT_PORT/bench/trace-payloads")"
    echo "  fake agent has received $PAYLOADS trace payloads so far"
    [[ "$PAYLOADS" -gt 0 ]] || echo "  WARNING: tracer sent no traces; the agent baseline is not exercising the trace pipeline" >&2
  fi
  kill "$APP_PID" 2>/dev/null || true
  wait "$APP_PID" 2>/dev/null || true
  for _ in $(seq 1 100); do port_in_use "$APP_PORT" || break; sleep 0.1; done
done

META="date=$(date -u +%Y-%m-%dT%H:%M:%SZ);host=$(uname -srm);cpus=$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo ?)"
META+=";java=$("$JAVA" -version 2>&1 | head -1 | tr -d '"');mysql=$MYSQL_VERSION (loopback)"
AGENT_JAR_SHOWN="$(redact "$AGENT_JAR")"
META+=";agentJar=${AGENT_JAR_SHOWN:-none};agentCommit=$(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || echo ?)"
META+=";startupRuns=$STARTUP_RUNS;warmupS=$WARMUP;durationS=$DURATION;connections=$CONNECTIONS;heap=$HEAP"
"${TOOLS[@]}" com.example.stmtcachebench.tools.Report "$RESULTS" "$OUT_DIR" "$META"

redact "== done: $OUT_DIR/summary.md"
cat "$OUT_DIR/summary.md"
[[ -f "$OUT_DIR/micro.md" ]] && cat "$OUT_DIR/micro.md"
exit 0
