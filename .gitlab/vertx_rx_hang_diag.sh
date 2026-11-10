#!/usr/bin/env bash
# DIAGNOSTIC ONLY (branch mo.atie/vertx-rx-hang-b-capture-error, never merged).
#
# Runs the vertx-rx-3.5 forked tests over and over to catch the rare "hang B" CI timeout.
# The module build aborts the forked test JVMs on the first StackOverflowError (hs_err in /tmp),
# and DumpHangedTestPlugin takes thread/heap dumps of a hanged task before its 20 minutes timeout.
#
# Each Gradle invocation runs 'forkedTest', where every test spec runs VERTX_DIAG_REPS+1 times
# (generated subclasses), VERTX_DIAG_FORKS test JVMs at once, then 'latestDepForkedTest'.
# Invocations repeat until VERTX_DIAG_BUDGET_SECONDS elapsed, a hang, or VERTX_DIAG_MAX_ABORTS aborts.
#
# Test JVMs also log every thrown exception (-Xlog:exceptions) to /tmp; the logs are kept only for an
# invocation that hanged, to find an Error other than StackOverflowError escaping on the event loop.
# VERTX_DIAG_XSS optionally lowers the thread stack size to make a deep-recursion overflow likelier.
#
# Everything lands in ./reports/vertx-diag, which .gitlab/collect_reports.sh tars into reports.tar.
# The job fails if anything was captured, so a red job means "look at the artifacts".

set -uo pipefail

MODULE=":dd-java-agent:instrumentation:vertx:vertx-rx-3.5"
BUILD_DIR="${VERTX_DIAG_BUILD_DIR:-workspace/dd-java-agent/instrumentation/vertx/vertx-rx-3.5/build}"
REPS="${VERTX_DIAG_REPS:-10}"
FORKS="${VERTX_DIAG_FORKS:-4}"
XSS="${VERTX_DIAG_XSS:-default}"
BUDGET_SECONDS="${VERTX_DIAG_BUDGET_SECONDS:-3000}"
MAX_ABORTS="${VERTX_DIAG_MAX_ABORTS:-3}"
OUT="./reports/vertx-diag"
SUMMARY="$OUT/summary.txt"

mkdir -p "$OUT/hs_err"

tasks=("$MODULE:forkedTest")
if [ "${VERTX_DIAG_LATEST_DEP:-true}" = "true" ]; then
  tasks+=("$MODULE:latestDepForkedTest")
fi

jvm_args="-Xlog:exceptions=info:file=/tmp/vxdiag_exceptions_%p.log:time,uptime,tid:filecount=2,filesize=40m"
if [ "$XSS" != "default" ]; then
  jvm_args="$jvm_args -Xss$XSS"
fi

log() {
  echo "$*" | tee -a "$SUMMARY"
}

# Number of JUnit XML files and summed failures+errors in a test-results directory.
count_results() {
  local dir="$1" files=0 bad=0
  if [ -d "$dir" ]; then
    files=$(find "$dir" -maxdepth 1 -name 'TEST-*.xml' | wc -l | tr -d ' ')
    bad=$(find "$dir" -maxdepth 1 -name 'TEST-*.xml' -exec head -c 2000 {} \; |
      grep -oE '<testsuite [^>]*>' | sed -E 's/.* failures="([0-9]+)" errors="([0-9]+)".*/\1 \2/' |
      awk '{s += $1 + $2} END {print s + 0}')
  fi
  echo "$files $bad"
}

log "job=$CI_JOB_NAME testJvm=$testJvm reps=$REPS forks=$FORKS xss=$XSS budget=${BUDGET_SECONDS}s"

start=$SECONDS
iter=0
total_spec_runs=0
total_latest_runs=0
total_test_failures=0
total_aborts=0
total_hangs=0

while [ $((SECONDS - start)) -lt "$BUDGET_SECONDS" ]; do
  iter=$((iter + 1))
  iter_log="$OUT/iter-$iter.log"
  iter_start=$SECONDS
  rm -f /tmp/vxdiag_exceptions_*.log*

  # -Prerun.tests.vertx-rx-3.5 disables output reuse (up-to-date and build cache) for the module's
  # test tasks, so every invocation really runs the tests.
  # shellcheck disable=SC2086
  ./gradlew "${tasks[@]}" $GRADLE_PARAMS -PtestJvm="$testJvm" \
    -PvertxHangDiagReps="$REPS" -PvertxHangDiagForks="$FORKS" -PvertxHangDiagJvmArgs="$jvm_args" \
    -Prerun.tests.vertx-rx-3.5 $GRADLE_ARGS --continue --console=plain > "$iter_log" 2>&1
  rc=$?

  # Outcomes in this invocation. A spec run is one test JVM (one JUnit XML file).
  read -r spec_runs spec_bad < <(count_results "$BUILD_DIR/test-results/forkedTest")
  read -r latest_runs latest_bad < <(count_results "$BUILD_DIR/test-results/latestDepForkedTest")
  failed_tasks=$(grep -E "^> Task $MODULE:(forkedTest|latestDepForkedTest) FAILED" "$iter_log" | awk '{print $3}' | sed 's/.*://' | paste -sd, -)
  hangs=$(grep -cE "exceeded its configured timeout" "$iter_log")
  crashes=$(grep -cE "finished with non-zero exit value" "$iter_log")

  # Aborts: hs_err files written by forked test JVMs during this invocation.
  aborts=0
  for f in /tmp/hs_err_pid*.log; do
    [ -e "$f" ] || continue
    dest="$OUT/hs_err/iter-$iter-$(basename "$f")"
    mv "$f" "$dest"
    if grep -q "StackOverflowError" "$dest"; then
      aborts=$((aborts + 1))
    fi
    log "  hs_err: $dest ($(grep -m1 -E "Saw |fatal error|Internal Error" "$dest" | head -c 200)) thread: $(grep -m1 '^Current thread' "$dest" | head -c 200)"
  done

  # Keep the JUnit XML (with the captured DEBUG output) of failed tasks; the next invocation
  # overwrites build/test-results.
  if [ -n "$failed_tasks" ]; then
    for t in ${failed_tasks//,/ }; do
      if [ -d "$BUILD_DIR/test-results/$t" ]; then
        mkdir -p "$OUT/iter-$iter-test-results/$t"
        cp "$BUILD_DIR/test-results/$t/"*.xml "$OUT/iter-$iter-test-results/$t/" 2>/dev/null || true
      fi
    done
  fi

  if [ "$hangs" -gt 0 ]; then
    # Keep the exception logs of the hanged invocation.
    mkdir -p "$OUT/iter-$iter-exceptions"
    for f in /tmp/vxdiag_exceptions_*.log*; do
      [ -e "$f" ] && gzip -c "$f" > "$OUT/iter-$iter-exceptions/$(basename "$f").gz"
    done
    # Keep heap dumps only for JVMs whose Test worker is stuck on Gradle's MessageHub (artifact size).
    dumps="$BUILD_DIR/dumps"
    keep=$(grep -l "MessageHub" "$dumps"/*-thread-dump-*.log 2>/dev/null | sed -E 's#.*/([0-9]+)-thread-dump-.*#\1#' | sort -u | paste -sd' ' -)
    if [ -n "$keep" ]; then
      for h in "$dumps"/*-heap-dump-*.hprof; do
        [ -e "$h" ] || continue
        pid=$(basename "$h" | cut -d- -f1)
        case " $keep " in *" $pid "*) ;; *) rm -f "$h" ;; esac
      done
    fi
    log "  hanged JVMs (MessageHub in thread dump): ${keep:-none found}; dumps: $(ls "$dumps" 2>/dev/null | paste -sd' ' -)"
  fi
  rm -f /tmp/vxdiag_exceptions_*.log*

  total_spec_runs=$((total_spec_runs + spec_runs))
  total_latest_runs=$((total_latest_runs + latest_runs))
  total_test_failures=$((total_test_failures + spec_bad + latest_bad))
  total_aborts=$((total_aborts + aborts))
  total_hangs=$((total_hangs + hangs))

  log "iter=$iter rc=$rc duration=$((SECONDS - iter_start))s spec_runs=$spec_runs latest_runs=$latest_runs test_failures=$((spec_bad + latest_bad)) failed=[${failed_tasks}] crashes=$crashes aborts=$aborts hangs=$hangs"

  if [ "$spec_runs" -eq 0 ]; then
    log "No forked test ran; Gradle invocation is broken. Tail of $iter_log:"
    tail -50 "$iter_log"
    break
  fi
  if [ "$rc" -ne 0 ] && [ "$aborts" -eq 0 ] && [ "$hangs" -eq 0 ]; then
    echo "--- iteration $iter failed without abort or hang; failure summary:"
    grep -E "FAILED|What went wrong|exit value" "$iter_log" | head -40
  fi
  if [ "$hangs" -gt 0 ]; then
    log "Hang captured (dumps in $BUILD_DIR/dumps); stopping."
    grep -E "Taking dumps|Finished dump collection|exceeded its configured timeout" "$iter_log" | tee -a "$SUMMARY"
    break
  fi
  if [ "$total_aborts" -ge "$MAX_ABORTS" ]; then
    log "Captured $total_aborts StackOverflowError aborts; stopping."
    break
  fi
done

log "TOTAL iterations=$iter spec_runs=$total_spec_runs latest_runs=$total_latest_runs test_failures=$total_test_failures aborts=$total_aborts hangs=$total_hangs elapsed=$((SECONDS - start))s"

if [ "$total_aborts" -gt 0 ] || [ "$total_hangs" -gt 0 ]; then
  exit 1
fi
exit 0
