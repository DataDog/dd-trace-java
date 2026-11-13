# Petclinic download and compilation stage
FROM eclipse-temurin:11-jammy as petclinic
    ARG CI_JOB_TOKEN

    RUN apt-get update \
      && apt-get -y install git openssh-client  \
      && apt-get -y clean \
      && rm -rf /var/lib/apt/lists/*

    COPY clone-debugger-demos.sh clone-debugger-demos.sh
    RUN --mount=type=ssh ./clone-debugger-demos.sh

FROM registry.ddbuild.io/images/benchmarking-platform-tools-ubuntu:latest
    ARG CI_JOB_TOKEN

    RUN --mount=type=ssh bp-install k6 datadog-agent

    RUN set -ex;\
      apt-get update;\
      apt-get -y install --no-install-recommends \
          procps gettext-base unzip openjdk-11-jdk-headless numactl linux-tools-common linux-tools-generic linux-tools-5.15.0-1057-aws;\
      apt-get -y clean;\
      rm -rf /var/lib/apt/lists/*;

    COPY --from=petclinic /spring-petclinic.jar /app/spring-petclinic.jar
    ENV PETCLINIC=/app/spring-petclinic.jar

    # required for append-report
    RUN pip install numpy scipy

