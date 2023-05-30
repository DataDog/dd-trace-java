#!/usr/bin/env bash
set -uo pipefail
NAME=$1
COMMAND=$2
echo " === running debugger java exploration tests === "
echo "Downloading java agent..."
curl -OL https://binaries.ddbuild.io/slydog-dd-trace-java/e0daeff9258b19b2e071c8a7382d902bc46b46ea/dd-java-agent-debugger.jar
export JAVA_TOOL_OPTIONS="-javaagent:`pwd`/dd-java-agent-debugger.jar -Ddatadog.slf4j.simpleLogger.log.com.datadog.debugger=debug -Ddd.trace.enabled=false -Ddd.debugger.enabled=true -Ddd.debugger.instrument.the.world=true -Ddd.debugger.classfile.dump.enabled=false -Ddd.debugger.verify.bytecode=true -Ddd.debugger.exclude.file=/exploration-tests/exclude.txt"
echo "$JAVA_TOOL_OPTIONS"
cd $NAME
echo "Building repository $NAME..."
eval "$COMMAND 2>agent.log"
exit_code=$?
echo "exit_code=$exit_code"
if [ -n "$exit_code" ] && [ $exit_code -ne 0 ]; then cat $NAME/target/surefire-reports/*.dumpstream; fi

