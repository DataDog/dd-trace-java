# Petclinic download and compilation stage
FROM eclipse-temurin:17-jammy as petclinic

ARG SPRING_PETCLINIC_COMMIT=cefaf55dd124d0635abfe857c3c99a3d3ea62017

RUN apt-get update \
  && apt-get -y install git  \
  && apt-get -y clean \
  && rm -rf /var/lib/apt/lists/*

RUN set -eux;\
  git init spring-petclinic;\
  cd spring-petclinic;\
  git remote add origin https://github.com/spring-projects/spring-petclinic.git;\
  git fetch --depth 1 origin ${SPRING_PETCLINIC_COMMIT};\
  git checkout ${SPRING_PETCLINIC_COMMIT};\
  ./mvnw dependency:go-offline

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

# Dacapo download
FROM debian:bookworm-slim as dacapo
RUN apt-get update \
  && apt-get -y install wget unzip \
  && apt-get -y clean \
  && rm -rf /var/lib/apt/lists/*

ARG DACAPO_VERSION=23.11-chopin
# The data for the big benchmarks is removed too ensure the final docker image is not too big
RUN wget -nv -O dacapo.zip https://download.dacapobench.org/chopin/dacapo-$DACAPO_VERSION.zip \
	&& mkdir /dacapo \
	&& unzip dacapo.zip -d /dacapo/ \
	&& rm -rf /dacapo/dacapo-$DACAPO_VERSION/dat/luindex \
	&& rm -rf /dacapo/dacapo-$DACAPO_VERSION/dat/lusearch \
	&& rm -rf /dacapo/dacapo-$DACAPO_VERSION/dat/graphchi \
	&& rm dacapo.zip

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

ARG SIRUN_VERSION=0.1.11
RUN wget -O sirun.tar.gz https://github.com/DataDog/sirun/releases/download/v$SIRUN_VERSION/sirun-v$SIRUN_VERSION-x86_64-unknown-linux-musl.tar.gz \
	&& tar -xzf sirun.tar.gz \
	&& rm sirun.tar.gz \
  && mv sirun /usr/bin/sirun

ARG K6_VERSION=0.45.1
RUN wget -O k6.tar.gz https://github.com/grafana/k6/releases/download/v$K6_VERSION/k6-v$K6_VERSION-linux-amd64.tar.gz \
	&& tar --strip-components=1 -xzf k6.tar.gz \
	&& rm k6.tar.gz \
  && mv k6 /usr/bin/k6

RUN mkdir -p /app

COPY --from=petclinic /spring-petclinic.jar /app/spring-petclinic.jar
ENV PETCLINIC=/app/spring-petclinic.jar

COPY --from=insecure-bank /insecure-bank.war /app/insecure-bank.war
ENV INSECURE_BANK=/app/insecure-bank.war

COPY --from=dacapo /dacapo/ /app/
ARG DACAPO_VERSION=23.11-chopin
ENV DACAPO=/app/dacapo-$DACAPO_VERSION.jar
