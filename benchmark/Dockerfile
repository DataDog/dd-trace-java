# Petclinic download and compilation stage
FROM eclipse-temurin:17-jammy as petclinic

RUN apt-get update \
  && apt-get -y install git  \
  && apt-get -y clean \
  && rm -rf /var/lib/apt/lists/*

RUN git clone --depth 1 --branch main --single-branch https://github.com/spring-projects/spring-petclinic.git \
  && cd spring-petclinic \
  && ./mvnw dependency:go-offline

RUN cd spring-petclinic \
  && ./mvnw package -Dmaven.test.skip=true \
  && cp target/*.jar /spring-petclinic.jar


# Insecure bank download and compilation stage
FROM eclipse-temurin:17-jammy as insecure-bank

RUN apt-get update \
  && apt-get -y install git  \
  && apt-get -y clean \
  && rm -rf /var/lib/apt/lists/*

RUN git clone --depth 1 --branch malvarez/spring-boot --single-branch https://github.com/hdiv/insecure-bank.git \
  && cd insecure-bank \
  && ./gradlew -q dependencies

RUN cd insecure-bank \
  && ./gradlew bootWar \
  && cp build/libs/*.war /insecure-bank.war


# K6 distribution
FROM grafana/xk6 as k6

USER 0:0
RUN xk6 build \
    --with github.com/szkiba/xk6-faker@latest \
    --output /k6


# sirun distribution
FROM debian:bookworm-slim as sirun

RUN apt-get update \
  && apt-get -y install wget \
  && apt-get -y clean \
  && rm -rf /var/lib/apt/lists/*

USER 0:0
ARG SIRUN_VERSION=0.1.11
RUN wget -O sirun.tar.gz https://github.com/DataDog/sirun/releases/download/v$SIRUN_VERSION/sirun-v$SIRUN_VERSION-x86_64-unknown-linux-musl.tar.gz \
	&& tar -xzf sirun.tar.gz \
	&& rm sirun.tar.gz


FROM debian:bookworm-slim

RUN apt-get update \
  && apt-get -y install git curl wget procps gettext-base \
  && apt-get -y clean \
  && rm -rf /var/lib/apt/lists/*

COPY --from=eclipse-temurin:8-jammy /opt/java/openjdk /usr/lib/jvm/8
COPY --from=eclipse-temurin:11-jammy /opt/java/openjdk /usr/lib/jvm/11
COPY --from=eclipse-temurin:17-jammy /opt/java/openjdk /usr/lib/jvm/17

RUN rm -rf \
    /usr/lib/jvm/*/man \
    /usr/lib/jvm/*/src.zip \
    /usr/lib/jvm/*/lib/src.zip \
    /usr/lib/jvm/*/demo \
    /usr/lib/jvm/*/sample

ENV JAVA_8_HOME=/usr/lib/jvm/8
ENV JAVA_11_HOME=/usr/lib/jvm/11
ENV JAVA_17_HOME=/usr/lib/jvm/17
ENV JAVA_HOME=${JAVA_8_HOME}
ENV PATH=${PATH}:${JAVA_HOME}/bin

COPY --from=k6 /k6 /usr/bin/k6
COPY --from=sirun /sirun /usr/bin/sirun

RUN mkdir -p /app

COPY --from=petclinic /spring-petclinic.jar /app/spring-petclinic.jar
ENV PETCLINIC=/app/spring-petclinic.jar

COPY --from=insecure-bank /insecure-bank.war /app/insecure-bank.war
ENV INSECURE_BANK=/app/insecure-bank.war
