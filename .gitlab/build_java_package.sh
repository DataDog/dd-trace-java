#!/bin/bash

if [ -z "$CI_COMMIT_TAG" ] ; then
  source ../upstream.env
  VERSION=$UPSTREAM_TRACER_VERSION$CI_VERSION_SUFFIX
else
  VERSION=${CI_COMMIT_TAG##v}
fi

echo -n "$VERSION" > auto_inject-java.version

cp ../workspace/dd-java-agent/build/libs/*.jar dd-java-agent.jar
source common_build_functions.sh

fpm_wrapper "datadog-apm-library-java" "$VERSION" \
  --input-type dir \
  --url "https://github.com/DataDog/dd-trace-java" \
  --license "Apache License 2.0" \
  --description "Datadog APM client library for Java" \
  dd-java-agent.jar="$LIBRARIES_INSTALL_BASE/java/dd-java-agent.jar" \
  auto_inject-java.version="$LIBRARIES_INSTALL_BASE/java/version"
