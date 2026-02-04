#!/usr/bin/env bash
SERVICE_NAME="dd-trace-java"
CACHE_TYPE=$1
TEST_JVM=$2

# JAVA_???_HOME are set in the base image for each used JDK https://github.com/DataDog/dd-trace-java-docker-build/blob/master/Dockerfile#L86
JAVA_HOME="JAVA_${TEST_JVM}_HOME"
JAVA_BIN="${!JAVA_HOME}/bin/java"
if [ ! -x "$JAVA_BIN" ]; then
    JAVA_BIN=$(which java)
fi

# Extract Java properties from the JVM used to run the tests
JAVA_PROPS=$($JAVA_BIN -XshowSettings:properties -version 2>&1)
java_prop() {
    local PROP_NAME=$1
    echo "$JAVA_PROPS" | grep "$PROP_NAME" | head -n1 | cut -d'=' -f2 | xargs
}

# Upload test results to CI Visibility
junit_upload() {
    # based on tracer implementation: https://github.com/DataDog/dd-trace-java/blob/master/dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/decorator/TestDecorator.java#L55-L77
    # Overwriting the tag with the GitHub repo URL instead of the GitLab one. Otherwise, some Test Optimization features won't work.

    # Parse DD_TAGS environment variable and convert to --tags arguments
    # Using bash array for proper argument quoting
    local dd_tags_args=()
    if [ -n "$DD_TAGS" ]; then
        # Split DD_TAGS by comma and create --tags argument for each
        IFS=',' read -ra TAG_ARRAY <<< "$DD_TAGS"
        for tag in "${TAG_ARRAY[@]}"; do
            dd_tags_args+=(--tags "$tag")
        done
    fi

    DD_API_KEY=$1 \
        datadog-ci junit upload --service $SERVICE_NAME \
        --logs \
        --tags "test.traits:{\"category\":[\"$CACHE_TYPE\"]}" \
        --tags "runtime.name:$(java_prop java.runtime.name)" \
        --tags "runtime.vendor:$(java_prop java.vendor)" \
        --tags "runtime.version:$(java_prop java.version)" \
        --tags "os.architecture:$(java_prop os.arch)" \
        --tags "os.platform:$(java_prop os.name)" \
        --tags "os.version:$(java_prop os.version)" \
        --tags "git.repository_url:https://github.com/DataDog/dd-trace-java" \
        "${dd_tags_args[@]}" \
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
