#!/bin/sh

JAVA_HOME=$JAVA_HOME_8_X64 JAVA_8_HOME=$JAVA_HOME_8_X64 JAVA_11_HOME=$JAVA_HOME_11_X64 JAVA_17_HOME=$JAVA_HOME_17_X64 ./gradlew dd-java-agent:build dd-java-agent:shadowJar --build-cache --parallel --no-daemon --max-workers=8
cp workspace/dd-java-agent/build/libs/dd-java-agent-*.jar lib-injection/
rm lib-injection/*-sources.jar lib-injection/*-javadoc.jar
mv lib-injection/*.jar lib-injection/dd-java-agent.jar
echo "Java tracer copied to lib-injection folder"
ls lib-injection/