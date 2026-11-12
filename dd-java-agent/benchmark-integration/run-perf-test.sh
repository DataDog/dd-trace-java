#!/usr/bin/env bash

# A script for measuring a server's throughput with or without a java agent.
test_csv_file=/tmp/perf_results.csv
server_output=/tmp/server_output.txt
server_type=$1
server_package=$2
agent_jars=("${@:3}")
server_pid=""
agent_state=""
if [[ "$server_package" = "" ]] || [[ "$server_type" != "play-zip" && "$server_type" != "jar" ]]; then
    echo "usage: ./run-perf-test.sh [play-zip|jar] path-to-server-package path-to-agent1 path-to-agent2..."
    echo ""
    echo "[play-zip|jar] : Specify whether the server will be in zip format (play server) or jar format"
    echo "path-to-server-package : Must be a jar or binary zip package which creates an http server on local port 8080 when started."
    echo "    Note: if the server package is a zip, then the script will attempt to unzip to a temp directory and run the server from there."
    echo "path-to-agent*     : Each must be a javaagent jar, or NoAgent."
    echo ""
    echo "Example: This will run the perf tests against myserver.jar. It will run against no agent as a baseline, then against myagent-1.0.jar."
    echo "  ./run-perf-test.sh /tmp/myserve.jar NoAgent /tmp/myagent-1.0.jar"
    echo ""
    echo "Test results are saved to $test_csv_file"
    exit 1
fi

# Datadog Agent detection drives the writer, and therefore WHICH REGIME we measure:
#   agent up   -> DDAgentWriter  -> full pipeline (request-thread work + background serializer)
#   agent down -> LoggingWriter  -> agent-unreachable regime (foreground only, traces discarded)
# check_agent refreshes this; it is called before every variant so a mid-run agent death is not silent.
agent_port="${TRACE_AGENT_PORT:-8126}"
function check_agent {
    # Probe the APM receiver rather than lsof: the Datadog Agent runs as user _dd-agent, so lsof (run
    # as us) can't see its socket. The Agent's own CPU isn't attributed here; the JFR is the CPU signal.
    if nc -z localhost "$agent_port" >/dev/null 2>&1; then
        agent_state="up"; writer_type="DDAgentWriter"
    else
        agent_state="down"; writer_type="LoggingWriter"
    fi
}
function regime_banner {
    if [ "$agent_state" = "up" ]; then
        echo ">> REGIME: full pipeline -- Datadog Agent reachable on :$agent_port, DDAgentWriter"
    else
        echo ">> REGIME: agent-unreachable -- nothing on :$agent_port, LoggingWriter (traces discarded)"
    fi
}
# EXPECT_AGENT=up|down|any (default any). When up/down, abort if reality differs -- guards an
# unattended sweep from silently measuring the wrong regime.
function assert_expected_agent {
    local expect="${EXPECT_AGENT:-any}"
    if [ "$expect" != "any" ] && [ "$expect" != "$agent_state" ]; then
        echo "ERROR: EXPECT_AGENT=$expect but the Datadog Agent on :$agent_port is '$agent_state'." >&2
        regime_banner >&2
        echo "Aborting to avoid measuring the wrong regime (set EXPECT_AGENT=any to override)." >&2
        [ -n "$server_pid" ] && kill "$server_pid" 2>/dev/null
        exit 3
    fi
}

check_agent
regime_banner
assert_expected_agent

# Settings file precedence: $PERF_SETTINGS env override, then a local perf-test-settings.rc,
# then the checked-in defaults. (run-petclinic.sh points PERF_SETTINGS at the PetClinic rc.)
settings_file="${PERF_SETTINGS:-}"
if [ -z "$settings_file" ]; then
    if [ -f perf-test-settings.rc ]; then
        settings_file="perf-test-settings.rc"
    else
        settings_file="perf-test-default-settings.rc"
    fi
fi
echo "loading settings from $settings_file"
cat "$settings_file"
. "$settings_file"

# Server heap. Fixed (Xms=Xmx) so GC behavior is stable and comparable across a sweep. Override via
# the settings file (server_heap=...) or the PERF_HEAP env var; defaults to 256m. Sweep the same
# versions at different heaps to expose the ample-vs-tight-heap regime.
server_heap="${PERF_HEAP:-${server_heap:-256m}}"
echo "server heap: -Xms$server_heap -Xmx$server_heap"

# Repeats per endpoint (one warmup, then N back-to-back measurement windows, no server restart).
# Throughput on a shared dev box is noisy -- repeats + reported stdev turn that into a real signal
# instead of a single noisy sample. Override via the settings file (test_repeats=...) or PERF_REPEATS.
test_repeats="${PERF_REPEATS:-${test_repeats:-1}}"
echo "repeats per endpoint: $test_repeats"

# wrk load shape. PERF_CONNECTIONS / PERF_THREADS override the settings file, e.g. to find the
# connection count where the NoAgent baseline stops gaining throughput.
test_num_connections="${PERF_CONNECTIONS:-$test_num_connections}"
test_num_threads="${PERF_THREADS:-$test_num_threads}"
echo "wrk: $test_num_connections connections, $test_num_threads threads"

# Extra server JVM options for every variant (NoAgent included), space-separated -- e.g. a scenario's
# -XX:ActiveProcessorCount / GC choice. From the settings file (server_jvm_args=...) or PERF_JVM_ARGS.
read -r -a server_jvm_args <<< "${PERF_JVM_ARGS:-${server_jvm_args:-}}"
echo "extra server JVM args: ${server_jvm_args[*]:-(none)}"
echo ""
echo ""

# /usr/bin/time differs by platform: macOS (BSD) uses -l and prints RSS in bytes as a leading
# number; GNU/Linux uses -v and prints "Maximum resident set size (kbytes): N". Detect which.
if /usr/bin/time -l true >/dev/null 2>&1; then
    time_flag="-l"
else
    time_flag="-v"
fi

unzipped_server_path=""

# Major version of the java on PATH (8 for "1.8").
java_major=$(java -XshowSettings:properties -version 2>&1 \
    | awk -F' = ' '/java.specification.version/ { v = $2; sub(/^1\./, "", v); print v }')

# Heap transitions in a -Xlog:gc log ("... 50M->10M(256M) ..."), one per GC pause. Only pause lines
# count: the gc+heap lines carry per-space transitions too (Serial's "DefNew: 34944K->4352K(...)").
heap_transition_re='[0-9]+[KMG]->[0-9]+[KMG]\('
function gc_transitions {
    grep -E "Pause" "$1" 2>/dev/null | grep -cE "$heap_transition_re" || true
}

# G1 region size in MB from the gc+init header ("Heap Region Size: 1M"), or empty (e.g. Serial GC).
function gc_region_mb {
    awk '/Heap Region Size:/ { v = $NF; u = substr(v, length(v)); n = v + 0; print (u == "K" ? n / 1024 : n); exit }' "$1" 2>/dev/null
}

# Middle- and old-age signals for GC log lines $2 (exclusive) to $3 (inclusive) of log $1, with G1
# region size $4 MB: "<promoted KB> <median live MB after GC> <mean survivor MB>". Promotion is the
# growth of the old generation across collections (G1 "Old regions: a->b", Serial "Tenured: a(cap)->b(cap)");
# shrinking (mixed or full collections) counts as zero, so it's promotion into old, not net growth.
function gc_window_stats {
    awk -v lo="$2" -v hi="$3" -v region="${4:-0}" '
        function kb(x,   n, u) { n = x + 0; u = substr(x, length(x)); return u == "G" ? n * 1048576 : (u == "M" ? n * 1024 : n) }
        NR <= lo || NR > hi { next }
        /Pause/ && match($0, /[0-9]+[KMG]->[0-9]+[KMG]\(/) {
            split(substr($0, RSTART, RLENGTH - 1), heap, "->"); live[++nl] = kb(heap[2]) / 1024
        }
        /Old regions:/ && match($0, /[0-9]+->[0-9]+/) {
            split(substr($0, RSTART, RLENGTH), r, "->"); if (r[2] > r[1]) promoted += (r[2] - r[1]) * region * 1024
        }
        # Serial logs "used(capacity)->used(capacity)": "Tenured: 0K(43712K)->2077K(43712K)".
        /Tenured:/ && match($0, /Tenured: [0-9]+[KMG]\([0-9]+[KMG]\)->[0-9]+[KMG]/) {
            split(substr($0, RSTART + 9, RLENGTH - 9), t, "->"); sub(/\(.*/, "", t[1])
            if (kb(t[2]) > kb(t[1])) promoted += kb(t[2]) - kb(t[1])
        }
        /Survivor regions:/ && match($0, /[0-9]+->[0-9]+/) {
            split(substr($0, RSTART, RLENGTH), s, "->"); surv += s[2] * region; ns++
        }
        # The Serial survivor space is "From: 0K(2176K)->2176K(2176K)".
        /From: / && match($0, /From: [0-9]+[KMG]\([0-9]+[KMG]\)->[0-9]+[KMG]/) {
            split(substr($0, RSTART + 6, RLENGTH - 6), d, "->"); sub(/\(.*/, "", d[1]); surv += kb(d[2]) / 1024; ns++
        }
        END {
            n = asort_live()
            printf "%.0f %.1f %.1f", promoted, (nl ? median : 0), (ns ? surv / ns : 0)
        }
        function asort_live(   i, j, t) {
            for (i = 2; i <= nl; i++) { t = live[i]; for (j = i - 1; j >= 1 && live[j] > t; j--) live[j + 1] = live[j]; live[j + 1] = t }
            median = nl % 2 ? live[(nl + 1) / 2] : (live[nl / 2] + live[nl / 2 + 1]) / 2
            return nl
        }' "$1"
}

# PERF_WAIT_IDLE=<load average>: before each variant, wait until port 8080 is free (no other
# benchmark's server) and the 1-minute load average is below the threshold for 3 one-minute checks,
# so a sweep never runs on top of another one. Unset, variants start right away.
function wait_idle {
    [ -n "${PERF_WAIT_IDLE:-}" ] || return 0
    local ok=0 load
    while [ "$ok" -lt "${PERF_WAIT_IDLE_CHECKS:-3}" ]; do
        load=$(sysctl -n vm.loadavg 2>/dev/null | awk '{ print $2 }')
        [ -z "$load" ] && load=$(awk '{ print $1 }' /proc/loadavg 2>/dev/null)
        if ! nc -z localhost 8080 >/dev/null 2>&1 && awk -v l="${load:-0}" -v t="$PERF_WAIT_IDLE" 'BEGIN { exit !(l < t) }'; then
            ok=$((ok + 1))
        else
            ok=0
        fi
        [ "$ok" -lt "${PERF_WAIT_IDLE_CHECKS:-3}" ] && sleep 60
    done
}
# KB allocated between transitions $2 (exclusive) and $3 (inclusive) of GC log $1: each GC's heap
# before it, minus the heap after the previous GC. Off by at most one GC interval at each end, which
# averages out over the hundreds of GCs in a measured window.
function gc_allocated_kb {
    awk -v lo="$2" -v hi="$3" '
        function kb(x,   n, u) { n = x + 0; u = substr(x, length(x)); return u == "G" ? n * 1048576 : (u == "M" ? n * 1024 : n) }
        /Pause/ && match($0, /[0-9]+[KMG]->[0-9]+[KMG]\(/) {
            split(substr($0, RSTART, RLENGTH - 1), heap, "->"); i++
            if (i > lo && i <= hi) total += kb(heap[1]) - after
            after = kb(heap[2])
        }
        END { printf "%.0f", total }' "$1"
}

# CPU time (whole seconds) a process has used so far.
function cpu_seconds {
    ps -o time= -p "$1" | awk -F'[:\.]' '{ print ($1 * 3600) + ($2 * 60) + $3 }'
}

# Kill a process and all of its descendants, children first.
function kill_tree {
    local child
    for child in $(pgrep -P "$1"); do
        kill_tree "$child"
    done
    kill "$1" 2>/dev/null
}

# True when the jar is the OpenTelemetry Java agent (as named by run-petclinic.sh's otel variant).
function is_otel_agent {
    [ -f "$1" ] && [[ "$(basename "$1")" == opentelemetry-javaagent* ]]
}

# Version label for an agent jar: "otel-<Implementation-Version>" for the OpenTelemetry agent, whose
# main class doesn't print one; otherwise what the Datadog agent's main class prints.
function agent_version_of {
    if is_otel_agent "$1"; then
        echo "otel-$(unzip -p "$1" META-INF/MANIFEST.MF | awk -F': ' '/^Implementation-Version:/ { print $2 }' | tr -d '\r')"
    else
        java -jar "$1" 2>/dev/null
    fi
}

# Start up server passed into the script
# Blocks until server is bound to local port 8080
function start_server {
    agent_jar="$1"
    variant_label="$2"
    # JVM options as an array, so paths with spaces (agent jar, JFR, GC log) stay one argument each.
    run_java_args=()
    if is_otel_agent "$agent_jar"; then
        # Traces only, over OTLP/http to the same endpoint the Datadog variants use; metrics and logs
        # exporters off so neither side pays for signals the other doesn't send. The default sampler
        # (parentbased_always_on) follows the sampled upstream traceparent, as the Datadog agent does.
        run_java_args+=("-javaagent:$agent_jar" -Dotel.javaagent.logging=none -Dotel.service.name=perf-test-app
            -Dotel.traces.exporter=otlp -Dotel.exporter.otlp.protocol=http/protobuf
            "-Dotel.exporter.otlp.endpoint=http://127.0.0.1:$agent_port"
            -Dotel.metrics.exporter=none -Dotel.logs.exporter=none)
    elif [ "$agent_jar" != "" -a -f "$agent_jar" ]; then
        run_java_args+=("-javaagent:$agent_jar" -Ddatadog.slf4j.simpleLogger.defaultLogLevel=off "-Ddd.writer.type=$writer_type" "-Ddd.trace.agent.port=$agent_port" -Ddd.service.name=perf-test-app)
    fi

    # Opt-in JFR (PERF_JFR=1): one recording per variant, named by label so runs don't collide.
    # settings=profile captures jdk.ExecutionSample + jdk.ObjectAllocationSample for analyze-jfr.sh.
    if [ "${PERF_JFR:-}" = "1" ]; then
        jfr_file="/tmp/perf_${variant_label}.jfr"
        rm -f "$jfr_file"
        run_java_args+=("-XX:StartFlightRecording=settings=profile,filename=$jfr_file,dumponexit=true")
        echo "JFR enabled -> $jfr_file"
    fi
    # GC log per variant, so test_endpoint can count young GCs inside each measured window. Unified
    # logging (-Xlog) needs JDK 9+; on JDK 8 the YGC/1k column is left empty.
    run_java_args+=("-Xms$server_heap" "-Xmx$server_heap" "${server_jvm_args[@]}" "${variant_jvm_args[@]}")
    gc_log=""
    if [ "$java_major" -ge 9 ] 2>/dev/null; then
        gc_log="/tmp/perf_gc_${variant_label}.log"
        rm -f "$gc_log"
        # gc+heap adds per-space sizes after each collection (G1 region counts, Serial young/tenured),
        # and gc+init the G1 region size, for the promotion / live-set / survivor columns.
        run_java_args+=("-Xlog:gc,gc+heap,gc+init:file=$gc_log")
    fi

    if [ "$server_type" = "jar" ]; then
      echo "starting server: java ${run_java_args[*]} -jar $server_package"
      # Server stdout -> /dev/null: Spring logs the /oups crash-endpoint RuntimeException to stdout
      # per request; under load that leaked 790K stack traces (~5G) into the run log and got the task
      # killed. Readiness is HTTP-polled and the pid comes from lsof, so stdout is not needed here.
      { /usr/bin/time $time_flag java "${run_java_args[@]}" -jar "$server_package" ; } 2> $server_output 1>/dev/null &
    else
      # make a temp directory to hold the unzipped server
      unzip_temp=`mktemp -d`
      # perform the unzipping of the play zip
      unzip "${server_package}" -d "${unzip_temp}" &> /dev/null

      if [ $? -eq 0 ]; then
        echo "Unzipped server package at ${unzip_temp}"
      else
        echo "Failed to unzip server package to ${unzip_temp}"
        exit 2
      fi

      # get the unzipped directory name
      unzipped_dirname=`basename ${unzip_temp}/*`
      # unzipped server location, will be removed when the server is stopped
      unzipped_server_path=${unzip_temp}

      # it appears the binary script will always be named main at the time of writing
      # no matter what the zip file is named.
      play_script=${unzipped_server_path}/${unzipped_dirname}/bin/main
      # The Gradle-built zip doesn't keep the launcher's executable bit.
      chmod +x "${play_script}"

      # have to use env to set JAVA_OPTS because of a gradle play plugin bug:
      # https://github.com/gradle/gradle/issues/4471
      # JAVA_OPTS must stay one quoted argument: split on spaces, only the first flag reaches it and
      # env tries to run the next one as the command. The Play launcher splits JAVA_OPTS on spaces
      # itself, so on Play an agent path with spaces still can't work.
      echo "starting server: env JAVA_OPTS=\"${run_java_args[*]}\" ${play_script}"
      { /usr/bin/time $time_flag env "JAVA_OPTS=${run_java_args[*]}" "${play_script}" ; } 2> $server_output 1>/dev/null &
    fi
    local launch_pid=$!

    # Block until the server actually SERVES HTTP 200 -- not just binds the TCP port. The java agent
    # slows startup, so a TCP-only check (nc -z) let wrk start before the app was serving under
    # load, producing `connect` socket errors and a bogus ~26x throughput collapse on the agent
    # runs. Poll the first configured endpoint for a real 200 (curl if present, else python3), with a
    # timeout; servers don't all serve / (the Jetty and Play test apps only serve /work).
    local ready_url="${endpoints[${test_order[0]}]}"
    ready_deadline=$((SECONDS + 180))
    until { command -v curl >/dev/null 2>&1 && curl -sf -o /dev/null "$ready_url" ; } \
        || python3 -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen(sys.argv[1],timeout=2).getcode()==200 else 1)" "$ready_url" >/dev/null 2>&1 ; do
        if [ "$SECONDS" -ge "$ready_deadline" ]; then
            echo "ERROR: server did not serve HTTP 200 on $ready_url within 180s -- aborting" >&2
            # server_pid isn't known yet, so stop what was launched: the time wrapper and the JVM
            # under it, which would otherwise outlive the script and hold the port.
            kill_tree "$launch_pid"
            exit 1
        fi
        sleep 0.5
    done
    # Brief settle so first-hit JIT/classloading doesn't skew the wrk warmup.
    sleep 2
    server_pid=$(lsof -i tcp:8080 | awk '$8 == "TCP" { print $2 }' | uniq)
    echo "server $server_pid started"
}

# Send a kill signal to the running server
# and block until the server is stopped
function stop_server {
    echo "Stopping $server_pid"
    kill $server_pid
    # A JVM thrashing on a too-small heap can take many minutes to run its shutdown hooks; don't let
    # one stall the sweep.
    local waited=0
    while kill -0 "$server_pid" 2>/dev/null && [ "$waited" -lt "${PERF_STOP_TIMEOUT:-60}" ]; do
        sleep 1; waited=$((waited + 1))
    done
    if kill -0 "$server_pid" 2>/dev/null; then
        echo "server $server_pid still running after ${PERF_STOP_TIMEOUT:-60}s -- killing it"
        kill -9 "$server_pid" 2>/dev/null
    fi
    wait
    server_pid=""
    # clean up the unzipped server
    if [[ ${unzipped_server_path} != "" ]] && [[ ${server_type} = "play-zip" ]] && [[ -d ${unzipped_server_path} ]]; then
      echo "Cleaning up unzipped server at "${unzipped_server_path}
      rm -rf ${unzipped_server_path}
    fi
}

# Warmup once, then run wrk $test_repeats times (no server restart between repeats) against a
# single endpoint. Echos out the raw wrk output files, one per repeat, space-separated; each has a
# <file>.gc sidecar with the young GCs counted inside that measured window.
# PERF_WRK_SCRIPT, if set, is passed to wrk as a Lua script (e.g. wrk-upstream-headers.lua).
function test_endpoint {
    url=$1
    local script_args=()
    [ -n "${PERF_WRK_SCRIPT:-}" ] && script_args=(-s "$PERF_WRK_SCRIPT")
    # warmup
    wrk -c $test_num_connections -t$test_num_threads -d ${test_warmup_seconds}s "${script_args[@]}" $url >/dev/null

    local results=()
    for ((rep = 1; rep <= test_repeats; rep++)); do
        wrk_results=/tmp/wrk_results.$(date +%s%N)
        local gc_before gc_after heap_before heap_after line_before line_after
        if [ -n "$gc_log" ]; then
            gc_before=$(grep -c 'Pause Young' "$gc_log" 2>/dev/null || true)
            heap_before=$(gc_transitions "$gc_log")
            line_before=$(wc -l < "$gc_log" 2>/dev/null || echo 0)
        fi
        wrk --latency -c $test_num_connections -t$test_num_threads -d ${test_time_seconds}s "${script_args[@]}" $url > "$wrk_results"
        if [ -n "$gc_log" ]; then
            gc_after=$(grep -c 'Pause Young' "$gc_log" 2>/dev/null || true)
            heap_after=$(gc_transitions "$gc_log")
            line_after=$(wc -l < "$gc_log" 2>/dev/null || echo 0)
            # "<young GCs> <KB allocated> <promoted KB> <median live MB> <mean survivor MB>" inside
            # this measured window
            echo "$(( ${gc_after:-0} - ${gc_before:-0} )) $(gc_allocated_kb "$gc_log" "${heap_before:-0}" "${heap_after:-0}") $(gc_window_stats "$gc_log" "${line_before:-0}" "${line_after:-0}" "$(gc_region_mb "$gc_log")")" > "$wrk_results.gc"
        else
            : > "$wrk_results.gc"
        fi
        # Sanity gate: with the HTTP-200 readiness check above, wrk should never see socket connect
        # errors. If it does, the app was not healthy under load and the row would be garbage (the
        # failure mode behind the bogus 26x sweep) -- abort loudly instead of recording it.
        if grep -qE 'Socket errors:.*connect [1-9]' "$wrk_results"; then
            echo "ERROR: wrk reported socket connect errors on $url (repeat $rep/$test_repeats) -- server unhealthy under load:" >&2
            grep -E 'Socket errors|Requests/sec' "$wrk_results" >&2
            exit 1
        fi
        results+=("$wrk_results")
    done
    fixed_rate_phase "$url" > "${results[0]}.fixed"
    echo "${results[@]}"
}

# Fixed-rate latency phase (PERF_FIXED_RATE req/s for PERF_FIXED_SECONDS, default 60): vegeta sends
# requests on a fixed schedule whether or not earlier ones have returned, so a server stall shows up
# in the percentiles instead of slowing the client down, and every variant gets the same offered
# load. wrk at saturation gives neither, which is why the headline p90/p99 come from here. Prints
# "<achieved rate> <p90 ms> <p99 ms> <success ratio>", or nothing when PERF_FIXED_RATE isn't set.
# Requests carry upstream trace headers whenever wrk's do (PERF_WRK_SCRIPT set), with the same
# UPSTREAM_SAMPLED_FRACTION.
#
# vegeta opens a new connection whenever every open one is busy, so a brief stall snowballs into
# thousands of connections and exhausts local ports. -max-connections caps that: workers stay
# unlimited, so a request still starts on schedule and its wait for a connection counts as latency.
# "localhost" is replaced by 127.0.0.1 because Go dials ::1 and 127.0.0.1 both.
function fixed_rate_phase {
    local url=${1/localhost/127.0.0.1}
    [ "${PERF_FIXED_RATE:-0}" -gt 0 ] 2>/dev/null || return 0
    local fraction="${UPSTREAM_SAMPLED_FRACTION:-1}"
    local targets="/tmp/perf_vegeta_targets.$(echo "$url" | cksum | cut -d' ' -f1)${PERF_WRK_SCRIPT:+.upstream-$fraction}.json"
    if [ ! -s "$targets" ]; then
        python3 "$(dirname "${BASH_SOURCE[0]}")/vegeta-targets.py" "$url" \
            ${PERF_WRK_SCRIPT:+--upstream-headers --sampled-fraction "$fraction"} > "$targets"
    fi
    vegeta attack -format=json -targets="$targets" -rate="$PERF_FIXED_RATE" -duration="${PERF_FIXED_SECONDS:-60}s" \
        -max-connections="${PERF_FIXED_CONNECTIONS:-256}" \
        | vegeta report -type=json \
        | python3 -c 'import json, sys
r = json.load(sys.stdin); l = r["latencies"]
print("%.2f %.3f %.3f %.4f" % (r["rate"], l["90th"] / 1e6, l["99th"] / 1e6, r["success"]))'
}

# Mean of plain numeric values (no unit suffix), e.g. throughput.
function mean_plain {
    local sum=0 count=0
    for v in "$@"; do
        sum=$(echo "$sum + $v" | bc)
        count=$((count + 1))
    done
    echo "scale=2; $sum / $count" | bc
}

# Sample stdev of plain numeric values; 0 when fewer than 2 samples (single repeat).
function stdev_plain {
    if [ "$#" -le 1 ]; then
        echo "0.00"
        return
    fi
    local mean sumsq=0
    mean=$(mean_plain "$@")
    for v in "$@"; do
        sumsq=$(echo "$sumsq + ($v - $mean) ^ 2" | bc)
    done
    echo "scale=2; sqrt($sumsq / ($# - 1))" | bc
}

# Mean of values sharing a unit suffix (e.g. "7.16ms" "6.20ms"). Assumes wrk keeps the same unit
# across repeats of the same endpoint/load -- if not (rare, only on a load-regime change mid-run),
# warn and fall back to the first repeat rather than silently averaging mismatched units.
function mean_with_unit {
    local unit sum=0 count=0
    unit=$(echo "$1" | sed -E 's/^-?[0-9.]+//')
    for v in "$@"; do
        if [ "$(echo "$v" | sed -E 's/^-?[0-9.]+//')" != "$unit" ]; then
            echo "WARN: mixed units across repeats ($1 vs $v) -- reporting first repeat only" >&2
            echo "$1"
            return
        fi
        sum=$(echo "$sum + $(echo "$v" | sed -E 's/[a-zA-Z%]+$//')" | bc)
        count=$((count + 1))
    done
    echo "scale=2; $sum / $count" | bc | awk -v u="$unit" '{printf "%.2f%s", $1, u}'
}


# PERF_SINK_STATS_URL, if set, is an Agent stub's /_stats (lines of "<path> <requests> <bytes>").
# Every agent variant must deliver traces to it during its run; a variant that delivered none was
# measuring a broken writer path, so the sweep aborts rather than recording it.
function sink_trace_requests {
    curl -s -m 3 "$PERF_SINK_STATS_URL" | awk '$1 ~ /\/traces$/ { n += $2 } END { print n + 0 }'
}

# Bytes the stub received on trace endpoints. Two variants sending the same payload format can be
# compared by trace bytes per served request: fewer means the variant dropped (or never sent) traces.
function sink_trace_bytes {
    curl -s -m 3 "$PERF_SINK_STATS_URL" | awk '$1 ~ /\/traces$/ { b += $3 } END { printf "%.0f", b + 0 }'
}

trap 'stop_server; exit' SIGINT
trap 'kill $server_pid; exit' SIGTERM
header='Client Version'
for label in "${test_order[@]}"; do
    header="$header,$label Latency,$label p90,$label p99,$label Throughput,$label Throughput Stdev,$label YGC/1k,$label KB/req"
    header="$header,$label Fixed Rate,$label Fixed p90 ms,$label Fixed p99 ms,$label Fixed Success"
    header="$header,$label Promoted KB/req,$label Live MB,$label Survivor MB"
done
header="$header,Server CPU Burn,Server Max RSS,Server Start RSS,Server Load Increase RSS,Trace Payloads,Trace Bytes"
echo $header > $test_csv_file

variant_index=0
for agent_jar in "${agent_jars[@]}"; do
    variant_index=$((variant_index + 1))
    # Quiet cooldown between variants (skip before the first). Off unless the settings set it.
    if [ "${_did_first_variant:-}" = "1" ] && [ "${test_cooldown_seconds:-0}" -gt 0 ] 2>/dev/null; then
        echo "cooldown ${test_cooldown_seconds}s before next variant..."
        sleep "$test_cooldown_seconds"
    fi
    _did_first_variant=1
    # Re-detect the agent before each variant so a mid-sweep agent death aborts (with EXPECT_AGENT)
    # rather than silently degrading later runs to LoggingWriter.
    wait_idle
    check_agent
    assert_expected_agent
    echo "----Testing agent $agent_jar----"
    regime_banner
    # A variant may carry its own JVM flags as "<jar>|<flags>", e.g. a payload format to compare; they
    # follow PERF_JVM_ARGS and are appended to the variant's label.
    variant_jvm_args=()
    agent_flags=""
    if [[ "$agent_jar" == *"|"* ]]; then
        agent_flags="${agent_jar#*|}"
        agent_jar="${agent_jar%%|*}"
        read -r -a variant_jvm_args <<< "$agent_flags"
    fi
    if [ "$agent_jar" == "NoAgent" ]; then
        result_row="NoAgent"
        variant_label="noagent"
        start_server "" "$variant_label"
    else
        agent_version=$(agent_version_of "$agent_jar")
        result_row="$agent_version${agent_flags:+ [${agent_flags//,/;}]}"
        # sanitize the version into a filesystem-safe JFR label; fall back to the jar basename. The
        # variant's position keeps two builds reporting the same version from sharing artifacts.
        variant_label=$(echo "$agent_version" | tr -c 'A-Za-z0-9._-' '_' | sed 's/_*$//')
        [ -z "$variant_label" ] && variant_label=$(basename "$agent_jar" .jar)
        variant_label="${variant_label}_v${variant_index}"
        start_server "$agent_jar" "$variant_label"
    fi


    [ -n "${PERF_SINK_STATS_URL:-}" ] && sink_before=$(sink_trace_requests) && sink_bytes_before=$(sink_trace_bytes)
    server_start_cpu=$(cpu_seconds "$server_pid")
    server_start_rss=$(ps -o rss= -p "$server_pid")

    server_total_rss=0
    server_total_rss_count=0

    for t in "${test_order[@]}"; do
        label="$t"
        url="${endpoints[$label]}"
        echo "--Testing $label -- $url--"
        # test_endpoint's `exit 1` (e.g. on wrk socket errors) only leaves this command
        # substitution, so check its status rather than recording an empty row.
        test_output=$(test_endpoint "$url") || { stop_server; exit 1; }
        test_output_files=($test_output)
        let server_total_rss=$server_total_rss+$(ps -o rss= -p "$server_pid")
        let server_total_rss_count=$server_total_rss_count+1

        latencies=()
        p90s=()
        p99s=()
        throughputs=()
        ygcs=()
        kb_per_req=()
        promoted_per_req=()
        live_mbs=()
        survivor_mbs=()
        fixed_rate="" fixed_p90="" fixed_p99="" fixed_success=""
        if [ -s "${test_output_files[0]}.fixed" ]; then
            read -r fixed_rate fixed_p90 fixed_p99 fixed_success < "${test_output_files[0]}.fixed"
            echo "  $label fixed rate: ${fixed_rate} req/s, p90 ${fixed_p90} ms, p99 ${fixed_p99} ms, success ${fixed_success}"
        fi
        rm -f "${test_output_files[0]}.fixed"
        for f in "${test_output_files[@]}"; do
            cat "$f"
            # --latency adds a "Latency Distribution" header line; the mean is on the plain "Latency" line.
            latencies+=("$(awk '$1 == "Latency" && $2 != "Distribution" { print $2 }' "$f")")
            p90s+=("$(awk '$1 == "90%" { print $2 }' "$f")")
            p99s+=("$(awk '$1 == "99%" { print $2 }' "$f")")
            throughputs+=("$(awk '$1 == "Requests/sec:" { print $2 }' "$f")")
            requests=$(awk '/requests in/ { print $1 }' "$f")
            if [ -s "$f.gc" ]; then
                read -r window_ygc window_kb window_promoted_kb window_live_mb window_survivor_mb < "$f.gc"
                ygcs+=("$(echo "scale=2; 1000 * $window_ygc / ${requests:-1}" | bc)")
                kb_per_req+=("$(echo "scale=2; $window_kb / ${requests:-1}" | bc)")
                if [ -n "${window_promoted_kb:-}" ]; then
                    promoted_per_req+=("$(awk -v p="$window_promoted_kb" -v n="${requests:-1}" 'BEGIN { printf "%.3f", p / n }')")
                    live_mbs+=("$window_live_mb")
                    survivor_mbs+=("$window_survivor_mb")
                fi
            fi
            rm -f "$f" "$f.gc"
        done
        avg_latency=$(mean_with_unit "${latencies[@]}")
        avg_p90=$(mean_with_unit "${p90s[@]}")
        avg_p99=$(mean_with_unit "${p99s[@]}")
        avg_ygc=""
        [ "${#ygcs[@]}" -gt 0 ] && avg_ygc=$(mean_plain "${ygcs[@]}")
        avg_kb_per_req=""
        [ "${#kb_per_req[@]}" -gt 0 ] && avg_kb_per_req=$(mean_plain "${kb_per_req[@]}")
        avg_promoted="" avg_live="" avg_survivor=""
        if [ "${#promoted_per_req[@]}" -gt 0 ]; then
            avg_promoted=$(mean_plain "${promoted_per_req[@]}")
            avg_live=$(mean_plain "${live_mbs[@]}")
            avg_survivor=$(mean_plain "${survivor_mbs[@]}")
        fi
        avg_throughput=$(mean_plain "${throughputs[@]}")
        stdev_throughput=$(stdev_plain "${throughputs[@]}")
        if [ "${#throughputs[@]}" -gt 1 ]; then
            echo "  $label: ${#throughputs[@]} repeats, throughput mean=$avg_throughput stdev=$stdev_throughput (${throughputs[*]})"
        fi
        result_row="$result_row,$avg_latency,$avg_p90,$avg_p99,$avg_throughput,$stdev_throughput,$avg_ygc,$avg_kb_per_req"
        result_row="$result_row,$fixed_rate,$fixed_p90,$fixed_p99,$fixed_success"
        result_row="$result_row,$avg_promoted,$avg_live,$avg_survivor"
    done

    server_stop_cpu=$(cpu_seconds "$server_pid")

    let server_cpu=$server_stop_cpu-$server_start_cpu

    server_load_increase_rss=$(echo "scale=2; ( $server_total_rss / $server_total_rss_count ) - $server_start_rss" | bc)

    # start_server reuses agent_jar (empty for NoAgent), so the baseline is told apart by its label.
    trace_payloads="" trace_bytes=""
    if [ -n "${PERF_SINK_STATS_URL:-}" ] && [ "$variant_label" != "noagent" ]; then
        sink_after=$(sink_trace_requests)
        if [ "${sink_after:-0}" -le "${sink_before:-0}" ]; then
            echo "ERROR: the Agent stub received no traces from $variant_label during its run -- aborting" >&2
            stop_server
            exit 1
        fi
        sink_bytes_after=$(sink_trace_bytes)
        trace_payloads=$((sink_after - sink_before))
        trace_bytes=$((sink_bytes_after - sink_bytes_before))
        echo "Agent stub received $trace_payloads trace payloads, $trace_bytes trace bytes from this variant"
    fi

    stop_server

    # Max RSS in bytes: BSD time -l prints "<bytes>  maximum resident set size"; GNU time -v prints
    # "Maximum resident set size (kbytes): <kB>".
    if [ "$time_flag" = "-l" ]; then
        server_max_rss=$(awk '/.* maximum resident set size/ { print $1 }' $server_output)
    else
        server_max_rss=$(awk -F': ' '/Maximum resident set size/ { print $2 * 1024 }' $server_output)
    fi
    rm $server_output

    echo "$result_row,$server_cpu,$server_max_rss,$server_start_rss,$server_load_increase_rss,$trace_payloads,$trace_bytes" >> $test_csv_file
    echo "----/Testing agent $agent_jar----"
    echo ""
done

echo ""
echo "DONE. Test results saved to $test_csv_file"
