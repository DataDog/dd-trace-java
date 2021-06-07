#!/usr/bin/env bash
SERVICE_NAME="dd-trace-java"

# JAVA_???_HOME are set in the base image for each used JDK https://github.com/DataDog/dd-trace-java-docker-build/blob/master/Dockerfile#L86
java_bin="\$JAVA_$2_HOME/bin/java"
if [ ! -x $java_bin ]; then
    java_bin=$(which java)
fi
java_props=$($java_bin -XshowSettings:properties -version 2>&1)
java_prop () {
    echo "$(echo "$java_props" | grep $1 | head -n1 | cut -d'=' -f2 | xargs)"
}

# based on tracer implementation: https://github.com/DataDog/dd-trace-java/blob/master/dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/decorator/TestDecorator.java#L55-L77
TAGS="test.bundle:$1,\
runtime.name:$(java_prop java.runtime.name),runtime.vendor:$(java_prop java.vendor),runtime.version:$(java_prop java.version),\
os.arch:$(java_prop os.arch),os.name:$(java_prop os.name),os.version:$(java_prop os.version)\
"

echo $TAGS

DD_TAGS="${TAGS}" datadog-ci junit upload --service $SERVICE_NAME ./results
