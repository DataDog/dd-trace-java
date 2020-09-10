#!/bin/bash

function init {
  bash lib/init_http.sh $TARGET $URL
  bash lib/gradle_init.sh $TARGET
}

function tests {
  (
    cd $TARGET
    export JAVA_OPTS=""
    # export JAVA_TOOL_OPTS? jetty, netty, spring reactor, 
    ./gradlew check
  )
}

function collect {
  find $TARGET -type f -iwholename "*/test-results/*" -name "*.xml" | xargs -n 1 -I % cp % $RESULTS
}
