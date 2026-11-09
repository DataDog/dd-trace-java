#!/usr/bin/env bash
# DIAGNOSTIC ONLY (branch mo.atie/vertx-rx-hang-b-capture-error, never merged).
#
# Runs the vertx-rx-3.5 forked tests over and over to catch the rare "hang B" CI timeout.
# The module build aborts the forked test JVMs on the first StackOverflowError (hs_err in /tmp),
# and DumpHangedTestPlugin takes thread/heap dumps of a hanged task before its 20 minutes timeout.
#
# Each Gradle invocation runs 'forkedTest' plus VERTX_DIAG_REPS-1 copies of it (forkedTestRepN, up
# to 3 at once through the shared forked-test limit) and 'latestDepForkedTest'. Invocations repeat
# until VERTX_DIAG_BUDGET_SECONDS elapsed, or until a hang, or VERTX_DIAG_MAX_ABORTS aborts.
#
# Everything lands in ./reports/vertx-diag, which .gitlab/collect_reports.sh tars into reports.tar.
# The job fails if anything was captured, so a red job means "look at the artifacts".

set -uo pipefail

MODULE=":dd-java-agent:instrumentation:vertx:vertx-rx-3.5"
BUILD_DIR="workspace/dd-java-agent/instrumentation/vertx/vertx-rx-3.5/build"
REPS="${VERTX_DIAG_REPS:-9}"
BUDGET_SECONDS="${VERTX_DIAG_BUDGET_SECONDS:-3000}"
MAX_ABORTS="${VERTX_DIAG_MAX_ABORTS:-3}"
OUT="./reports/vertx-diag"
SUMMARY="$OUT/summary.txt"

mkdir -p "$OUT/hs_err"

tasks=("$MODULE:forkedTest")
for ((rep = 2; rep <= REPS; rep++)); do
  tasks+=("$MODULE:forkedTestRep$rep")
done
if [ "${VERTX_DIAG_LATEST_DEP:-true}" = "true" ]; then
  tasks+=("$MODULE:latestDepForkedTest")
fi

log() {
  echo "$*" | tee -a "$SUMMARY"
}

log "job=$CI_JOB_NAME testJvm=$testJvm reps=$REPS budget=${BUDGET_SECONDS}s tasks=${#tasks[@]}"

start=$SECONDS
iter=0
total_runs=0
total_failed_tasks=0
total_aborts=0
total_hangs=0

while [ $((SECONDS - start)) -lt "$BUDGET_SECONDS" ]; do
  iter=$((iter + 1))
  iter_log="$OUT/iter-$iter.log"
  iter_start=$SECONDS

  # -Prerun.tests.vertx-rx-3.5 disables output reuse (up-to-date and build cache) for the module's
  # test tasks, so every invocation really runs the tests.
  # shellcheck disable=SC2086
  ./gradlew "${tasks[@]}" $GRADLE_PARAMS -PtestJvm="$testJvm" -PvertxHangDiagReps="$REPS" \
    -Prerun.tests.vertx-rx-3.5 $GRADLE_ARGS --continue --console=plain > "$iter_log" 2>&1
  rc=$?

  # Task outcomes in this invocation.
  # The plain console repeats a task header whenever its output interleaves with other tasks.
  ran=$(grep -oE "^> Task $MODULE:(forkedTest|forkedTestRep[0-9]+|latestDepForkedTest)( |$)" "$iter_log" | sed "s/ *$//" | sort -u | wc -l | tr -d ' ')
  failed_tasks=$(grep -E "^> Task $MODULE:(forkedTest|forkedTestRep[0-9]+|latestDepForkedTest) FAILED" "$iter_log" | awk '{print $3}' | sed 's/.*://' | paste -sd, -)
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
    log "  hs_err: $dest ($(grep -m1 -E "Internal Error|fatal error|Saw " "$dest" | head -c 200))"
  done

  # Keep the JUnit XML (with the captured DEBUG output) of tasks that failed in this invocation;
  # the next invocation overwrites build/test-results.
  if [ -n "$failed_tasks" ]; then
    for t in ${failed_tasks//,/ }; do
      if [ -d "$BUILD_DIR/test-results/$t" ]; then
        mkdir -p "$OUT/iter-$iter-test-results/$t"
        cp -r "$BUILD_DIR/test-results/$t/." "$OUT/iter-$iter-test-results/$t/" 2>/dev/null || true
      fi
    done
  fi

  total_runs=$((total_runs + ran))
  [ -n "$failed_tasks" ] && total_failed_tasks=$((total_failed_tasks + $(echo "$failed_tasks" | tr ',' '\n' | wc -l)))
  total_aborts=$((total_aborts + aborts))
  total_hangs=$((total_hangs + hangs))

  log "iter=$iter rc=$rc duration=$((SECONDS - iter_start))s ran=$ran failed=[${failed_tasks}] crashes=$crashes aborts=$aborts hangs=$hangs"

  if [ "$ran" -eq 0 ]; then
    log "No test task ran; Gradle invocation is broken. Tail of $iter_log:"
    tail -50 "$iter_log"
    break
  fi
  if [ "$rc" -ne 0 ] && [ "$aborts" -eq 0 ] && [ "$hangs" -eq 0 ]; then
    echo "--- iteration $iter failed without abort or hang; failure summary:"
    grep -E "FAILED|What went wrong|Exception|exit value" "$iter_log" | head -40
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

log "TOTAL iterations=$iter runs=$total_runs failed_tasks=$total_failed_tasks aborts=$total_aborts hangs=$total_hangs elapsed=$((SECONDS - start))s"

if [ "$total_aborts" -gt 0 ] || [ "$total_hangs" -gt 0 ]; then
  exit 1
fi
exit 0
