#!/usr/bin/env bash
SERVICE_NAME="dd-trace-java"
CACHE_TYPE=$1
TEST_JVM=${2:-}

# CI_JOB_NAME, CI_NODE_INDEX, and CI_NODE_TOTAL are read from GitLab CI environment

# JAVA_???_HOME are set in the base image for each used JDK https://github.com/DataDog/dd-trace-java-docker-build/blob/master/Dockerfile#L86
JAVA_PROPS=""
if [ -n "$TEST_JVM" ]; then
    JAVA_BIN=""
    if [[ "$TEST_JVM" =~ ^[A-Za-z0-9_]+$ ]]; then
        JAVA_HOME_VAR="JAVA_${TEST_JVM}_HOME"
        JAVA_HOME_VALUE="${!JAVA_HOME_VAR}"
        if [ -n "$JAVA_HOME_VALUE" ] && [ -x "$JAVA_HOME_VALUE/bin/java" ]; then
            JAVA_BIN="$JAVA_HOME_VALUE/bin/java"
        fi
    fi
    if [ -z "$JAVA_BIN" ]; then
        JAVA_BIN="$(command -v java)"
    fi
    JAVA_PROPS=$($JAVA_BIN -XshowSettings:properties -version 2>&1)
fi

java_prop() {
    local PROP_NAME=$1
    echo "$JAVA_PROPS" | grep "$PROP_NAME" | head -n1 | cut -d'=' -f2 | xargs
}

# Upload test results to CI Visibility
junit_upload() {
    # based on tracer implementation: https://github.com/DataDog/dd-trace-java/blob/master/dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/decorator/TestDecorator.java#L55-L77
    # Overwriting the tag with the GitHub repo URL instead of the GitLab one. Otherwise, some Test Optimization features won't work.

    # Build custom tags array directly from arguments
    local custom_tags_args=()

    # Extract job base name from CI_JOB_NAME (strip matrix suffix)
    local job_base_name="${CI_JOB_NAME%%:*}"

    # Add custom test configuration tags
    if [ -n "$TEST_JVM" ]; then
        custom_tags_args+=(--tags "test.configuration.jvm:${TEST_JVM}")
        custom_tags_args+=(--tags "runtime.name:$(java_prop java.runtime.name)")
        custom_tags_args+=(--tags "runtime.vendor:$(java_prop java.vendor)")
        custom_tags_args+=(--tags "runtime.version:$(java_prop java.version)")
        custom_tags_args+=(--tags "os.architecture:$(java_prop os.arch)")
        custom_tags_args+=(--tags "os.platform:$(java_prop os.name)")
        custom_tags_args+=(--tags "os.version:$(java_prop os.version)")
    fi
    if [ -n "$CI_NODE_INDEX" ] && [ -n "$CI_NODE_TOTAL" ]; then
        custom_tags_args+=(--tags "test.configuration.split:${CI_NODE_INDEX}/${CI_NODE_TOTAL}")
    fi
    if [ -n "$job_base_name" ]; then
        custom_tags_args+=(--tags "test.configuration.job_name:${job_base_name}")
    fi

    DD_API_KEY=$1 \
        datadog-ci junit upload --service $SERVICE_NAME \
        --logs \
        --tags "test.traits:{\"category\":[\"$CACHE_TYPE\"]}" \
        --tags "git.repository_url:https://github.com/DataDog/dd-trace-java" \
        "${custom_tags_args[@]}" \
        ./results
}

# Upload code coverage results to Datadog
coverage_upload() {
    DD_API_KEY=$1 \
    DD_GIT_REPOSITORY_URL=git@github.com:DataDog/dd-trace-java.git \
        datadog-ci coverage upload --ignored-paths=./test-published-dependencies .
}

# Upload test results to production environment like all other CI jobs
junit_upload "$DATADOG_API_KEY_PROD"
junit_upload_status=$?

coverage_upload "$DATADOG_API_KEY_PROD"
coverage_upload_status=$?

if [[ $junit_upload_status -ne 0 || $coverage_upload_status -ne 0 ]]; then
  exit 1
fi
