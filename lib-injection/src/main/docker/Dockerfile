FROM busybox

ARG UID=10000
RUN addgroup -g 10000 -S datadog \
    && adduser -S -G datadog -u ${UID} datadog
WORKDIR /datadog-init
ADD copy-lib.sh /datadog-init/copy-lib.sh
ADD dd-java-agent.jar /datadog-init/dd-java-agent.jar
USER ${UID}
