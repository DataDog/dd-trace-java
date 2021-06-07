#!/usr/bin/env bash
SERVICE_NAME="dd-trace-java"
TEST_BUNDLE=$1
JDK_STR=$2

TAGS="test.bundle:${TEST_BUNDLE}"
if [[ $JDK_STR =~ ([^0-9]*)([0-9]+) ]]; then
    JDK_NAME=${BASH_REMATCH[1]:-"adoptOpenJDK"}
    JDK_VERSION=${BASH_REMATCH[2]}
    TAGS="${TAGS},runtime.name:${JDK_NAME},runtime.version:${JDK_VERSION}"
fi

datadog-ci junit upload --service $SERVICE --tags $TAGS ./results
