
ARG REPO=adoptopenjdk
ARG TAG=8-jdk-hotspot-bionic
FROM ${REPO}:${TAG}

ARG SOURCE
ARG TAR_STRIP_COMPONENTS=1

RUN set -xe; \
    mkdir /target; \
    cd /target; \
    curl -o source.tar -L ${SOURCE}; \
    tar xf source.tar --strip-components=${TAR_STRIP_COMPONENTS}; \
    rm -rf source.tar
