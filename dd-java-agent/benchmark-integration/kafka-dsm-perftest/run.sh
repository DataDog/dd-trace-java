#!/usr/bin/env bash
# usage: run.sh <baseline|apm-only|apm+dsm> [optimization-slug]
#
# Runs the Kafka produce/consume overhead benchmark against the tracer built from the repo
# this script lives in (any worktree). Build the tracer first:
#   ./gradlew :dd-java-agent:shadowJar        (jar lands in dd-java-agent/build/libs/)
#
# Env: COMMIT_EVERY=100 WARMUP=5 COUNT=25 KAFKA_BOOTSTRAP_SERVERS=localhost:9092
#      KAFKA_TOPIC (default benchmark-topic-java-<scenario>-<slug>, '+' -> 'plus')
#      TIMEIT_OUTPUT (default results.java.<scenario>.json in this folder)
#      PROFILE=1 -> JFR over the timed produce+consume phases only; PROFILE_OUTPUT (.jfr path),
#                   plus <PROFILE_OUTPUT minus .jfr>.top.txt hot-method text
#      DD_JAVA_AGENT overrides the agent jar; DD_TRACE_AGENT_URL (default mock agent 127.0.0.1:18127)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(git -C "$HERE" rev-parse --show-toplevel)"
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"; export PATH="$JAVA_HOME/bin:$PATH"
S=${1:-baseline}; O=${2:-base}
case $S in baseline|apm-only|apm+dsm) ;; *) echo "unknown scenario: $S" >&2; exit 2;; esac
SC=${S//+/plus}

if [ ! -d "$HERE/target/lib" ]; then (cd "$HERE" && mvn -q -B package); fi

export KAFKA_TOPIC=${KAFKA_TOPIC:-benchmark-topic-java-$SC-$O}
export TIMEIT_OUTPUT=${TIMEIT_OUTPUT:-$HERE/results.java.$SC.json}
export PROFILE=${PROFILE:-0}
export DD_TRACE_STARTUP_LOGS=false DD_TRACE_AGENT_URL=${DD_TRACE_AGENT_URL:-http://127.0.0.1:18127}
CP="$HERE/target/classes:$HERE/target/lib/*"

if [ "$PROFILE" = 1 ]; then
  export PROFILE_OUTPUT=${PROFILE_OUTPUT:-$HERE/profile.java.$SC.jfr}
fi

if [ "$S" != baseline ]; then
  AGENT=${DD_JAVA_AGENT:-$(ls "$REPO_ROOT"/dd-java-agent/build/libs/dd-java-agent-*.jar 2>/dev/null \
    | grep -v -e -sources -e -javadoc | head -1)}
  [ -f "${AGENT:-}" ] || { echo "agent jar not found; run ./gradlew :dd-java-agent:shadowJar in $REPO_ROOT" >&2; exit 1; }
  echo "agent: $AGENT"
  AGENT_ARGS=(-javaagent:"$AGENT")
  [ "$S" = apm-only ] && export DD_DATA_STREAMS_ENABLED=false || export DD_DATA_STREAMS_ENABLED=true
else
  AGENT_ARGS=()
fi

java ${AGENT_ARGS[@]+"${AGENT_ARGS[@]}"} -cp "$CP" bench.Bench

if [ "$PROFILE" = 1 ]; then
  uv run --no-project python "$HERE/jfr_top.py" "$PROFILE_OUTPUT" > "${PROFILE_OUTPUT%.jfr}.top.txt"
  echo "profile: $PROFILE_OUTPUT (+ ${PROFILE_OUTPUT%.jfr}.top.txt)"
fi
