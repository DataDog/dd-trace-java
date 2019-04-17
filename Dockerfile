FROM openjdk:11

RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y maven moreutils awscli && apt-get -y clean && rm -rf /var/lib/apt/lists/*
