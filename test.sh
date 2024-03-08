export JAVA_TOOL_OPTIONS=-verbose:class

./gradlew :dd-java-agent:instrumentation:java-concurrent:latestDepTest -PtestJvm=21
