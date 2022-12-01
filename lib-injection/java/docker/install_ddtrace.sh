#!/bin/bash

set -eu

BINARIES_DIR=$1 #/binaries
TARGET_DIR=$2 #/dd-tracer
mkdir -p $2

if [ $(ls $BINARIES_DIR/dd-java-agent*.jar | wc -l) = 0 ]; then
    BUILD_URL="https://github.com/DataDog/dd-trace-java/releases/latest/download/dd-java-agent.jar"
    echo "install from Github release: $BUILD_URL"
    curl  -Lf -o $TARGET_DIR/dd-java-agent.jar $BUILD_URL

elif [ $(ls $BINARIES_DIR/dd-java-agent*.jar | wc -l) = 1 ]; then
    echo "Install local file $(ls $BINARIES_DIR/dd-java-agent*.jar)"
    cp $(ls $BINARIES_DIR/dd-java-agent*.jar) ${TARGET_DIR}/dd-java-agent.jar

else
    echo "Too many jar files in binaries"
    exit 1
fi

java -jar $TARGET_DIR/dd-java-agent.jar > $BINARIES_DIR/SYSTEM_TESTS_LIBRARY_VERSION

echo "Installed $(cat $BINARIES_DIR/SYSTEM_TESTS_LIBRARY_VERSION) java library"

touch $BINARIES_DIR/SYSTEM_TESTS_LIBDDWAF_VERSION

SYSTEM_TESTS_LIBRARY_VERSION=$(cat $BINARIES_DIR/SYSTEM_TESTS_LIBRARY_VERSION)

if [[ $SYSTEM_TESTS_LIBRARY_VERSION == 0.96* ]]; then
  echo "1.2.5" > $BINARIES_DIR/SYSTEM_TESTS_APPSEC_EVENT_RULES_VERSION
else
  bsdtar -O - -xf $TARGET_DIR/dd-java-agent.jar appsec/default_config.json | \
    grep rules_version | head -1 | awk -F'"' '{print $4;}' \
    > $BINARIES_DIR/SYSTEM_TESTS_APPSEC_EVENT_RULES_VERSION
fi

echo "dd-trace version: $(cat $BINARIES_DIR/SYSTEM_TESTS_LIBRARY_VERSION)"
echo "libddwaf version: $(cat $BINARIES_DIR/SYSTEM_TESTS_LIBDDWAF_VERSION)"
echo "rules version: $(cat $BINARIES_DIR/SYSTEM_TESTS_APPSEC_EVENT_RULES_VERSION)"

