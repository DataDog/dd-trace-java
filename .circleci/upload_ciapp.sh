#!/usr/bin/env bash
SERVICE_NAME="dd-trace-java"
PIPELINE_STAGE=$1
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
    DD_API_KEY=$1 \
        datadog-ci junit upload --service $SERVICE_NAME \
        --logs \
        --tags "test.traits:{\"marker\":[\"$PIPELINE_STAGE\"]}" \
        --tags "runtime.name:$(java_prop java.runtime.name)" \
        --tags "runtime.vendor:$(java_prop java.vendor)" \
        --tags "runtime.version:$(java_prop java.version)" \
        --tags "os.architecture:$(java_prop os.arch)" \
        --tags "os.platform:$(java_prop os.name)" \
        --tags "os.version:$(java_prop os.version)" \
        ./results
}

# Upload test results to production environment like all other CI jobs
junit_upload "$DATADOG_API_KEY_PROD"
