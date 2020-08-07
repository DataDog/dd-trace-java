#!/bin/bash

function init {
  bash lib/init_http.sh $TARGET $URL
  bash lib/gradle_init.sh $TARGET
}

function tests {
  (
    cd $TARGET
    ./gradlew check
  )
}

function collect {
  find $TARGET -type f -iwholename "*/test-results/*" -name "*.xml" | xargs -n 1 -I % cp % $RESULTS
}
