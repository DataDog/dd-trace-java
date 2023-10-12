FROM scratch AS java_agent
COPY dd-java-agent.jar /

FROM alpine:3.18.3

ARG UID=10000
RUN addgroup -g 10000 -S datadog \
    && adduser -S -G datadog -u ${UID} datadog
WORKDIR /datadog-init

COPY copy-lib.sh /datadog-init/copy-lib.sh
# using a separate stage for holding a jar - can allow us to build this dockerfile remotely, 
# and replace the jar - when needed
COPY --from=java_agent dd-java-agent.jar /datadog-init/dd-java-agent.jar
USER ${UID}
