
FROM eclipse-temurin:8 as java_agent_debug

# Install required bsdtar
RUN apt-get update && \
	apt-get install -y libarchive-tools

# Install tracer
COPY ./tooling/ci/ddtrace-info.sh ./tooling/ci/binaries* /binaries/
RUN /binaries/ddtrace-info.sh
RUN touch -c -a -m -d @0 /binaries/*

FROM scratch
COPY --from=java_agent_debug /binaries/dd-java-agent.jar /binaries/LIBRARY_VERSION /binaries/LIBDDWAF_VERSION /binaries/APPSEC_EVENT_RULES_VERSION /