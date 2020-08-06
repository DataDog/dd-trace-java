#!/bin/bash
TARGET=hibernate-5.4

function init {
  bash lib/init_http.sh $TARGET https://github.com/hibernate/hibernate-orm/archive/5.4.10.tar.gz
  bash lib/gradle_init.sh $TARGET
}

function tests {
  (
    cd $TARGET
    ./gradlew check
  )
}

function collect {
  echo 2
}
