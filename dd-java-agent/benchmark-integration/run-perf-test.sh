#!/usr/bin/env bash

# A script for measuring a server's throughput with or without a java agent.
test_csv_file=/tmp/perf_results.csv
server_output=/tmp/server_output.txt
server_type=$1
server_package=$2
agent_jars="${@:3}"
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

# Heap transitions in a -Xlog:gc log ("... 50M->10M(256M) ..."), one per GC pause.
heap_transition_re='[0-9]+[KMG]->[0-9]+[KMG]\('
function gc_transitions {
    grep -cE "$heap_transition_re" "$1" 2>/dev/null || true
}
# KB allocated between transitions $2 (exclusive) and $3 (inclusive) of GC log $1: each GC's heap
# before it, minus the heap after the previous GC. Off by at most one GC interval at each end, which
# averages out over the hundreds of GCs in a measured window.
function gc_allocated_kb {
    awk -v lo="$2" -v hi="$3" '
        function kb(x,   n, u) { n = x + 0; u = substr(x, length(x)); return u == "G" ? n * 1048576 : (u == "M" ? n * 1024 : n) }
        match($0, /[0-9]+[KMG]->[0-9]+[KMG]\(/) {
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

# Start up server passed into the script
# Blocks until server is bound to local port 8080
function start_server {
    agent_jar="$1"
    variant_label="$2"
    javaagent_arg=""
    if [ "$agent_jar" != "" -a -f "$agent_jar" ]; then
        javaagent_arg="-javaagent:$agent_jar -Ddatadog.slf4j.simpleLogger.defaultLogLevel=off -Ddd.writer.type=$writer_type -Ddd.trace.agent.port=$agent_port -Ddd.service.name=perf-test-app"
    fi

    # Opt-in JFR (PERF_JFR=1): one recording per variant, named by label so runs don't collide.
    # settings=profile captures jdk.ExecutionSample + jdk.ObjectAllocationSample for analyze-jfr.sh.
    jfr_arg=""
    if [ "${PERF_JFR:-}" = "1" ]; then
        jfr_file="/tmp/perf_${variant_label}.jfr"
        rm -f "$jfr_file"
        jfr_arg="-XX:StartFlightRecording=settings=profile,filename=$jfr_file,dumponexit=true"
        echo "JFR enabled -> $jfr_file"
    fi
    # GC log per variant, so test_endpoint can count young GCs inside each measured window. Unified
    # logging (-Xlog) needs JDK 9+; on JDK 8 the YGC/1k column is left empty.
    gc_log=""
    gc_arg=""
    if [ "$java_major" -ge 9 ] 2>/dev/null; then
        gc_log="/tmp/perf_gc_${variant_label}.log"
        rm -f "$gc_log"
        gc_arg="-Xlog:gc:file=$gc_log"
    fi
    run_java_args="$javaagent_arg $jfr_arg -Xms$server_heap -Xmx$server_heap $gc_arg"

    if [ "$server_type" = "jar" ]; then
      echo "starting server: java $run_java_args -jar $server_package"
      # Server stdout -> /dev/null: Spring logs the /oups crash-endpoint RuntimeException to stdout
      # per request; under load that leaked 790K stack traces (~5G) into the run log and got the task
      # killed. Readiness is HTTP-polled and the pid comes from lsof, so stdout is not needed here.
      { /usr/bin/time $time_flag java $run_java_args -jar $server_package ; } 2> $server_output 1>/dev/null &
    else
      # make a temp directory to hold the unzipped server
      unzip_temp=`mktemp -d`
      # perform the unzipping of the play zip
      unzip ${server_package} -d ${unzip_temp} &> /dev/null

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

      # have to use env to set JAVA_OPTS because of a gradle play plugin bug:
      # https://github.com/gradle/gradle/issues/4471
      utility_cmd="env JAVA_OPTS=${run_java_args} ${play_script}"

      echo "starting server: ${utility_cmd}"
      { /usr/bin/time $time_flag ${utility_cmd} ; } 2> $server_output 1>/dev/null &
    fi

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
        local gc_before gc_after heap_before heap_after
        if [ -n "$gc_log" ]; then
            gc_before=$(grep -c 'Pause Young' "$gc_log" 2>/dev/null || true)
            heap_before=$(gc_transitions "$gc_log")
        fi
        wrk --latency -c $test_num_connections -t$test_num_threads -d ${test_time_seconds}s "${script_args[@]}" $url > "$wrk_results"
        if [ -n "$gc_log" ]; then
            gc_after=$(grep -c 'Pause Young' "$gc_log" 2>/dev/null || true)
            heap_after=$(gc_transitions "$gc_log")
            # "<young GCs> <KB allocated>" inside this measured window
            echo "$(( ${gc_after:-0} - ${gc_before:-0} )) $(gc_allocated_kb "$gc_log" "${heap_before:-0}" "${heap_after:-0}")" > "$wrk_results.gc"
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
    echo "${results[@]}"
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

trap 'stop_server; exit' SIGINT
trap 'kill $server_pid; exit' SIGTERM
header='Client Version'
for label in "${test_order[@]}"; do
    header="$header,$label Latency,$label p99,$label Throughput,$label Throughput Stdev,$label YGC/1k,$label KB/req"
done
header="$header,Server CPU Burn,Server Max RSS,Server Start RSS,Server Load Increase RSS"
echo $header > $test_csv_file

variant_index=0
for agent_jar in $agent_jars; do
    variant_index=$((variant_index + 1))
    # Quiet cooldown between variants (skip before the first). Off unless the settings set it.
    if [ "${_did_first_variant:-}" = "1" ] && [ "${test_cooldown_seconds:-0}" -gt 0 ] 2>/dev/null; then
        echo "cooldown ${test_cooldown_seconds}s before next variant..."
        sleep "$test_cooldown_seconds"
    fi
    _did_first_variant=1
    # Re-detect the agent before each variant so a mid-sweep agent death aborts (with EXPECT_AGENT)
    # rather than silently degrading later runs to LoggingWriter.
    check_agent
    assert_expected_agent
    echo "----Testing agent $agent_jar----"
    regime_banner
    if [ "$agent_jar" == "NoAgent" ]; then
        result_row="NoAgent"
        variant_label="noagent"
        start_server "" "$variant_label"
    else
        agent_version=$(java -jar $agent_jar 2>/dev/null)
        result_row="$agent_version"
        # sanitize the version into a filesystem-safe JFR label; fall back to the jar basename. The
        # variant's position keeps two builds reporting the same version from sharing artifacts.
        variant_label=$(echo "$agent_version" | tr -c 'A-Za-z0-9._-' '_' | sed 's/_*$//')
        [ -z "$variant_label" ] && variant_label=$(basename "$agent_jar" .jar)
        variant_label="${variant_label}_v${variant_index}"
        start_server "$agent_jar" "$variant_label"
    fi


    [ -n "${PERF_SINK_STATS_URL:-}" ] && sink_before=$(sink_trace_requests)
    server_start_cpu=$(cpu_seconds "$server_pid")
    server_start_rss=$(ps -o rss= -p "$server_pid")

    server_total_rss=0
    server_total_rss_count=0

    for t in "${test_order[@]}"; do
        label="$t"
        url="${endpoints[$label]}"
        echo "--Testing $label -- $url--"
        test_output_files=($(test_endpoint $url))
        let server_total_rss=$server_total_rss+$(ps -o rss= -p "$server_pid")
        let server_total_rss_count=$server_total_rss_count+1

        latencies=()
        p99s=()
        throughputs=()
        ygcs=()
        kb_per_req=()
        for f in "${test_output_files[@]}"; do
            cat "$f"
            # --latency adds a "Latency Distribution" header line; the mean is on the plain "Latency" line.
            latencies+=("$(awk '$1 == "Latency" && $2 != "Distribution" { print $2 }' "$f")")
            p99s+=("$(awk '$1 == "99%" { print $2 }' "$f")")
            throughputs+=("$(awk '$1 == "Requests/sec:" { print $2 }' "$f")")
            requests=$(awk '/requests in/ { print $1 }' "$f")
            if [ -s "$f.gc" ]; then
                read -r window_ygc window_kb < "$f.gc"
                ygcs+=("$(echo "scale=2; 1000 * $window_ygc / ${requests:-1}" | bc)")
                kb_per_req+=("$(echo "scale=2; $window_kb / ${requests:-1}" | bc)")
            fi
            rm -f "$f" "$f.gc"
        done
        avg_latency=$(mean_with_unit "${latencies[@]}")
        avg_p99=$(mean_with_unit "${p99s[@]}")
        avg_ygc=""
        [ "${#ygcs[@]}" -gt 0 ] && avg_ygc=$(mean_plain "${ygcs[@]}")
        avg_kb_per_req=""
        [ "${#kb_per_req[@]}" -gt 0 ] && avg_kb_per_req=$(mean_plain "${kb_per_req[@]}")
        avg_throughput=$(mean_plain "${throughputs[@]}")
        stdev_throughput=$(stdev_plain "${throughputs[@]}")
        if [ "${#throughputs[@]}" -gt 1 ]; then
            echo "  $label: ${#throughputs[@]} repeats, throughput mean=$avg_throughput stdev=$stdev_throughput (${throughputs[*]})"
        fi
        result_row="$result_row,$avg_latency,$avg_p99,$avg_throughput,$stdev_throughput,$avg_ygc,$avg_kb_per_req"
    done

    server_stop_cpu=$(cpu_seconds "$server_pid")

    let server_cpu=$server_stop_cpu-$server_start_cpu

    server_load_increase_rss=$(echo "scale=2; ( $server_total_rss / $server_total_rss_count ) - $server_start_rss" | bc)

    # start_server reuses agent_jar (empty for NoAgent), so the baseline is told apart by its label.
    if [ -n "${PERF_SINK_STATS_URL:-}" ] && [ "$variant_label" != "noagent" ]; then
        sink_after=$(sink_trace_requests)
        if [ "${sink_after:-0}" -le "${sink_before:-0}" ]; then
            echo "ERROR: the Agent stub received no traces from $variant_label during its run -- aborting" >&2
            stop_server
            exit 1
        fi
        echo "Agent stub received $((sink_after - sink_before)) trace payloads from this variant"
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

    echo "$result_row,$server_cpu,$server_max_rss,$server_start_rss,$server_load_increase_rss" >> $test_csv_file
    echo "----/Testing agent $agent_jar----"
    echo ""
done

echo ""
echo "DONE. Test results saved to $test_csv_file"
