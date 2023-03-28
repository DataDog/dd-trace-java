#!/usr/bin/env bash
# Expands gradle targets for use in Circle CI parallelization.
# Preserves targets that are not parallelizable as well as their order.
set -eu

if [[ ${CI:-} = true ]]; then
    readonly BUILD_DIR="workspace/build"
else
    readonly BUILD_DIR="build"
fi

true >gradleTargets
for target in "$@"; do
    if [[ ${CIRCLE_NODE_TOTAL:-1} = 1 ]]; then
        echo "${target}" >>gradleTargets
    else
        if [[ "${target}" =~ ^(latestDepTest|:baseTest|:smokeTest|:instrumentationTest|:profilingTest|:debuggerTest)$ ]]; then
            ./gradlew ":writeProjects_${target/:/}"
            if [[ "${target}" = latestDepTest ]]; then
                test_task=latestDepTest
            else
                test_task=test
            fi
            cat "${BUILD_DIR}/gradle-project-list" |
                circleci tests split --split-by=timings |
                sed -e "s~$~:${test_task}~g" >>gradleTargets
        else
            echo "${target}" >>gradleTargets
        fi
    fi
done
echo "Gradle targets to run:"
cat gradleTargets
echo "Written to $(pwd)/gradleTargets"
