#!/usr/bin/env bash
set -uo pipefail
NAME=$1
COMMAND=$2
echo " === running debugger java exploration tests === "
echo "Downloading java agent..."
curl -Lo dd-java-agent.jar https://dtdg.co/latest-java-tracer
export JAVA_TOOL_OPTIONS="-javaagent:`pwd`/dd-java-agent.jar -Ddatadog.slf4j.simpleLogger.log.com.datadog.debugger=debug -Ddd.trace.enabled=false -Ddd.dynamic.instrumentation.enabled=true -Ddd.dynamic.instrumentation.instrument.the.world=true -Ddd.dynamic.instrumentation.classfile.dump.enabled=false -Ddd.dynamic.instrumentation.verify.bytecode=true -Ddd.dynamic.instrumentation.exclude.file=/exploration-tests/exclude.txt"
echo "$JAVA_TOOL_OPTIONS"
cd $NAME
echo "Building repository $NAME..."
eval "$COMMAND 2>agent.log"
exit_code=$?
echo "exit_code=$exit_code"
if [ -n "$exit_code" ] && [ $exit_code -ne 0 ]; then cat $NAME/target/surefire-reports/*.dumpstream; fi

