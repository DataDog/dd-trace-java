#!/bin/bash

if [ -z "$CI_COMMIT_TAG" ] ; then
  source ../upstream.env
  VERSION=$UPSTREAM_TRACER_VERSION$CI_VERSION_SUFFIX
else
  VERSION=${CI_COMMIT_TAG##v}
fi

mkdir -p sources
cp ../workspace/dd-java-agent/build/libs/*.jar sources/dd-java-agent.jar
echo -n "$VERSION" > sources/version
