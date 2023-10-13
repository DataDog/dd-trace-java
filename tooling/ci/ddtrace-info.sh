#!/bin/bash

set -eu


if [ $(ls /binaries/dd-java-agent*.jar | wc -l) != 1 ]; then
    echo "Too many jar files in binaries"
    exit 1
fi

java -jar /binaries/dd-java-agent.jar > /binaries/LIBRARY_VERSION

echo "Installed $(cat /binaries/LIBRARY_VERSION) java library"

touch /binaries/LIBDDWAF_VERSION

LIBRARY_VERSION=$(cat /binaries/LIBRARY_VERSION)

if [[ $LIBRARY_VERSION == 0.96* ]]; then
  echo "1.2.5" > /binaries/APPSEC_EVENT_RULES_VERSION
else
  bsdtar -O - -xf /binaries/dd-java-agent.jar appsec/default_config.json | \
    grep rules_version | head -1 | awk -F'"' '{print $4;}' \
    > /binaries/APPSEC_EVENT_RULES_VERSION
fi

echo "dd-trace version: $(cat /binaries/LIBRARY_VERSION)"
echo "libddwaf version: $(cat /binaries/LIBDDWAF_VERSION)"
echo "rules version: $(cat /binaries/APPSEC_EVENT_RULES_VERSION)"

