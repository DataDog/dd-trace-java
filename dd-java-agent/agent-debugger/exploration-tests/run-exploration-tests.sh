#!/usr/bin/env bash
set -uo pipefail
ITW_TYPE=${1:-"method"}
NAME=$2
COMMAND=$3
PROJECT_INCLUDE_FILE=${4:-}
PROJECT_EXCLUDE_FILE=${5:-}
echo " === running debugger java exploration tests === "
export JAVA_TOOL_OPTIONS="-javaagent:`pwd`/dd-java-agent.jar -Ddatadog.slf4j.simpleLogger.log.com.datadog.debugger=debug -Ddd.trace.enabled=false -Ddd.dynamic.instrumentation.enabled=true -Ddd.dynamic.instrumentation.instrument.the.world=$ITW_TYPE -Ddd.dynamic.instrumentation.classfile.dump.enabled=true -Ddd.dynamic.instrumentation.verify.bytecode=false -Ddd.dynamic.instrumentation.include.files=/exploration-tests/$PROJECT_INCLUDE_FILE -Ddd.dynamic.instrumentation.exclude.files=/exploration-tests/$PROJECT_EXCLUDE_FILE"
echo "$JAVA_TOOL_OPTIONS"
cd $NAME
echo "Building repository $NAME..."
eval "$COMMAND 2>agent.log"
exit_code=$?
echo "exit_code=$exit_code"
if [ -n "$exit_code" ] && [ $exit_code -ne 0 ];
then
  cat $NAME/target/surefire-reports/*.dumpstream;
fi
if [ ! -f "/tmp/debugger/instrumentation.log" ]; then
  echo "no instrumentation error"
  exit $exit_code;
fi
instrumentation_errors=`wc -l /tmp/debugger/instrumentation.log | cut -d' ' -f1`
if [ -n "$instrumentation_errors" ] && [ $instrumentation_errors -ne 0 ]; then
  echo "$instrumentation_errors errors in instrumentation.log"
  cat /tmp/debugger/instrumentation.log
  exit 1;
fi
