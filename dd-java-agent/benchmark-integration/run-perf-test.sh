#!/usr/bin/env bash

# A script for measuring a server's throughput with or without a java agent.
test_csv_file=/tmp/perf_results.csv
server_output=/tmp/server_output.txt
server_type=$1
server_package=$2
agent_jars="${@:3}"
server_pid=""
agent_pid=""
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
function check_agent {
    # Probe the APM receiver rather than lsof: the Datadog Agent runs as user _dd-agent, so lsof (run
    # as us) can't see its socket. Port is TRACE_AGENT_PORT (default 8126). agent_pid stays empty --
    # we don't attribute the system agent's CPU here; the JFR is the CPU signal.
    if nc -z localhost "${TRACE_AGENT_PORT:-8126}" >/dev/null 2>&1; then
        agent_state="up"; writer_type="DDAgentWriter"
    else
        agent_state="down"; writer_type="LoggingWriter"
    fi
    agent_pid=""
}
function regime_banner {
    local port="${TRACE_AGENT_PORT:-8126}"
    if [ "$agent_state" = "up" ]; then
        echo ">> REGIME: full pipeline -- Datadog Agent reachable on :$port, DDAgentWriter"
    else
        echo ">> REGIME: agent-unreachable -- nothing on :$port, LoggingWriter (traces discarded)"
    fi
}
# EXPECT_AGENT=up|down|any (default any). When up/down, abort if reality differs -- guards an
# unattended sweep from silently measuring the wrong regime.
function assert_expected_agent {
    local expect="${EXPECT_AGENT:-any}"
    if [ "$expect" != "any" ] && [ "$expect" != "$agent_state" ]; then
        echo "ERROR: EXPECT_AGENT=$expect but the Datadog Agent on :${TRACE_AGENT_PORT:-8126} is '$agent_state'." >&2
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

# Start up server passed into the script
# Blocks until server is bound to local port 8080
function start_server {
    agent_jar="$1"
    variant_label="${2:-run}"
    javaagent_arg=""
    if [ "$agent_jar" != "" -a -f "$agent_jar" ]; then
        javaagent_arg="-javaagent:$agent_jar -Ddatadog.slf4j.simpleLogger.defaultLogLevel=off -Ddd.writer.type=$writer_type -Ddd.trace.agent.port=${TRACE_AGENT_PORT:-8126} -Ddd.service.name=perf-test-app"
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
    run_java_args="$javaagent_arg $jfr_arg -Xms$server_heap -Xmx$server_heap"

    if [ "$server_type" = "jar" ]; then
      echo "starting server: java $run_java_args -jar $server_package"
      { /usr/bin/time $time_flag java $run_java_args -jar $server_package ; } 2> $server_output  &
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

      java_opts_env='JAVA_OPTS="'${run_java_args}'"'
      # it appears the binary script will always be named main at the time of writing
      # no matter what the zip file is named.
      play_script=${unzipped_server_path}/${unzipped_dirname}/bin/main

      # have to use env to set JAVA_OPTS because of a gradle play plugin bug:
      # https://github.com/gradle/gradle/issues/4471
      if [ -n "$(echo $run_java_args | tr -d ' ')" ]; then
        utility_cmd="env JAVA_OPTS=${run_java_args} ${play_script}"
      else
        utility_cmd="${play_script}"
      fi

      echo "starting server: ${utility_cmd}"
      { /usr/bin/time $time_flag ${utility_cmd} ; } 2> $server_output &
    fi

    # Block until the server actually SERVES HTTP 200 -- not just binds the TCP port. The java agent
    # slows startup, so a TCP-only check (nc -z) let wrk start before the app was serving under
    # load, producing `connect` socket errors and a bogus ~26x throughput collapse on the agent
    # runs. Poll the home endpoint for a real 200 (curl if present, else python3), with a timeout.
    ready_deadline=$((SECONDS + 180))
    until { command -v curl >/dev/null 2>&1 && curl -sf -o /dev/null http://localhost:8080/ ; } \
        || python3 -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://localhost:8080/',timeout=2).getcode()==200 else 1)" >/dev/null 2>&1 ; do
        if [ "$SECONDS" -ge "$ready_deadline" ]; then
            echo "ERROR: server did not serve HTTP 200 on / within 180s -- aborting" >&2
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
# single endpoint. Echos out the raw wrk output files, one per repeat, space-separated.
function test_endpoint {
    url=$1
    # warmup
    wrk -c $test_num_connections -t$test_num_threads -d ${test_warmup_seconds}s $url >/dev/null

    local results=()
    for ((rep = 1; rep <= test_repeats; rep++)); do
        wrk_results=/tmp/wrk_results.$(date +%s%N)
        wrk -c $test_num_connections -t$test_num_threads -d ${test_time_seconds}s $url > "$wrk_results"
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


trap 'stop_server; exit' SIGINT
trap 'kill $server_pid; exit' SIGTERM
header='Client Version'
for label in "${test_order[@]}"; do
    header="$header,$label Latency,$label Throughput,$label Throughput Stdev"
done
header="$header,Agent CPU Burn,Server CPU Burn,Agent RSS Delta,Server Max RSS,Server Start RSS,Server Load Increase RSS"
echo $header > $test_csv_file

for agent_jar in $agent_jars; do
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
        # sanitize the version into a filesystem-safe JFR label; fall back to the jar basename.
        variant_label=$(echo "$agent_version" | tr -c 'A-Za-z0-9._-' '_' | sed 's/_*$//')
        [ -z "$variant_label" ] && variant_label=$(basename "$agent_jar" .jar)
        start_server "$agent_jar" "$variant_label"
    fi


    if [ "$agent_pid" = "" ]; then
        agent_start_cpu=0
        agent_start_rss=0
    else
        agent_start_cpu=$(ps -o 'pid,time' | awk "\$1 == $agent_pid { print \$2 }" | awk -F'[:\.]' '{ print ($1 * 3600) + ($2 * 60) + $3 }')
        agent_start_rss=$(ps -o 'pid,rss' | awk "\$1 == $agent_pid { print \$2 }")
    fi
    server_start_cpu=$(ps -o time= -p "$server_pid" | awk -F'[:\.]' '{ print ($1 * 3600) + ($2 * 60) + $3 }')
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
        throughputs=()
        for f in "${test_output_files[@]}"; do
            cat "$f"
            latencies+=("$(awk '$1 == "Latency" { print $2 }' "$f")")
            throughputs+=("$(awk '$1 == "Requests/sec:" { print $2 }' "$f")")
            rm "$f"
        done
        avg_latency=$(mean_with_unit "${latencies[@]}")
        avg_throughput=$(mean_plain "${throughputs[@]}")
        stdev_throughput=$(stdev_plain "${throughputs[@]}")
        if [ "${#throughputs[@]}" -gt 1 ]; then
            echo "  $label: ${#throughputs[@]} repeats, throughput mean=$avg_throughput stdev=$stdev_throughput (${throughputs[*]})"
        fi
        result_row="$result_row,$avg_latency,$avg_throughput,$stdev_throughput"
    done

    if [ "$agent_pid" = "" ]; then
        agent_stop_cpu=0
        agent_stop_rss=0
    else
        agent_stop_cpu=$(ps -o 'pid,time' | awk "\$1 == $agent_pid { print \$2 }" | awk -F'[:\.]' '{ print ($1 * 3600) + ($2 * 60) + $3 }')
        agent_stop_rss=$(ps -o 'pid,rss' | awk "\$1 == $agent_pid { print \$2 }")
    fi
    server_stop_cpu=$(ps -o time= -p "$server_pid" | awk -F'[:\.]' '{ print ($1 * 3600) + ($2 * 60) + $3 }')

    let agent_cpu=$agent_stop_cpu-$agent_start_cpu
    let agent_rss=$agent_stop_rss-$agent_start_rss
    let server_cpu=$server_stop_cpu-$server_start_cpu

    server_load_increase_rss=$(echo "scale=2; ( $server_total_rss / $server_total_rss_count ) - $server_start_rss" | bc)

    stop_server

    server_max_rss=$(awk '/.* maximum resident set size/ { print $1 }' $server_output)
    rm $server_output

    echo "$result_row,$agent_cpu,$server_cpu,$agent_rss,$server_max_rss,$server_start_rss,$server_load_increase_rss" >> $test_csv_file
    echo "----/Testing agent $agent_jar----"
    echo ""
done

echo ""
echo "DONE. Test results saved to $test_csv_file"
