#!/usr/bin/env bash
# Calculats the exact targets that need to run.
# Used to optimize parallelization.
set -eu

# Stage name (core, flaky, instrumentation, smoke)
readonly STAGE_NAME="${1}"
shift

# Space-separated list of tasks to run (e.g. test).
readonly TASK_NAMES="${*:-test}"

# Space-separated list of paths to include.
PATH_INCLUDES=""
# Space-separated list of paths to exclude (priority over includes).
PATH_EXCLUDES="/application/ /latest-jdk-app/"
case "${STAGE_NAME}" in
core)
    PATH_INCLUDES="."
    PATH_EXCLUDES="/dd-smoke-tests/ /dd-java-agent/instrumentation/"
    ;;
agent-integration)
    PATH_INCLUDES="dd-trace-core"
    ;;
profiling)
    PATH_INCLUDES="dd-java-agent/agent-profiling"
    ;;
debugger)
    PATH_INCLUDES="dd-java-agent/agent-debugger"
    ;;
flaky)
    PATH_INCLUDES="."
    ;;
instrumentation)
    PATH_INCLUDES="dd-java-agent/instrumentation"
    ;;
smoke)
    PATH_INCLUDES="dd-smoke-tests"
    ;;
*)
    echo "Invalid stage: ${STAGE_NAME}"
    exit 1
    ;;
esac

cmd="find ${PATH_INCLUDES} -name 'build.gradle' | grep -v '\./build.gradle'"
for path in ${PATH_EXCLUDES}; do
    cmd="${cmd} | grep -v '${path}'"
done
cmd="${cmd} | sed -e 's~/build.gradle~:test~g'"
cmd="${cmd} | sed -e '"'s~\./~~g'"'"
cmd="${cmd} | sed -e 's~/~:~g' -e 's~^:*~:~g'"

# Do not run circleci CLI if not in CI, to ease local testing.
if [[ -n ${CI:-} ]]; then
    cmd="${cmd} | circleci tests split --split-by=timings"
fi

eval "${cmd}" | while read line; do
    for task in ${TASK_NAMES}; do
        echo "${line/%:test/:${task}}"
    done
done
