FROM busybox

RUN addgroup -g 1000 -S datadog \
    && adduser -S -G datadog -u 1000 datadog
WORKDIR /datadog-init
ADD copy-lib.sh /datadog-init/copy-lib.sh
ADD dd-java-agent.jar /datadog-init/dd-java-agent.jar
USER datadog
