#!/bin/sh

JAVA_HOME=$JAVA_HOME_8_X64 JAVA_8_HOME=$JAVA_HOME_8_X64 JAVA_11_HOME=$JAVA_HOME_11_X64 ./gradlew dd-java-agent:build dd-java-agent:shadowJar --build-cache --parallel --no-daemon --max-workers=8
cp ../workspace/dd-java-agent/build/libs/dd-java-agent-*.jar .
rm *-sources.jar *-javadoc.jar
mv *.jar dd-java-agent.jar