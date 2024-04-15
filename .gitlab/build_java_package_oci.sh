#!/bin/bash

if [ -z "$CI_COMMIT_TAG" ] ; then
  source ../upstream.env
  VERSION=$UPSTREAM_TRACER_VERSION$CI_VERSION_SUFFIX
else
  VERSION=${CI_COMMIT_TAG##v}
fi

echo -n "$VERSION" > auto_inject-java.version

mkdir -p sources
cp ../workspace/dd-java-agent/build/libs/*.jar sources/dd-java-agent.jar
cp auto_inject-java.version sources/version

datadog-package create \
    --version="$VERSION" \
    --package="datadog-apm-library-java" \
    --archive=true \
    --archive-path="datadog-apm-library-java-$VERSION-1.tar" \
    ./sources
