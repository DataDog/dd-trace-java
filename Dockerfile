FROM openjdk:11
# FROM openjdk:11-jdk

RUN apt update && apt install -y awscli moreutils && apt -y clean && rm -rf /var/lib/apt/lists/*

ARG MAVEN_VERSION=3.6.1
ARG USER_HOME_DIR="/root"
ARG SHA=b4880fb7a3d81edd190a029440cdf17f308621af68475a4fe976296e71ff4a4b546dd6d8a58aaafba334d309cc11e638c52808a4b0e818fc0fd544226d952544
ARG BASE_URL=https://apache.osuosl.org/maven/maven-3/${MAVEN_VERSION}/binaries

# Maven fails with 'Can't read cryptographic policy directory: unlimited'
# because it looks for $JAVA_HOME/conf/security/policy/unlimited but it is in
# /etc/java-9-openjdk/security/policy/unlimited
RUN ln -s /etc/java-11-openjdk /usr/lib/jvm/java-11-openjdk-$(dpkg --print-architecture)/conf

RUN mkdir -p /usr/share/maven /usr/share/maven/ref \
  && curl -fsSL -o /tmp/apache-maven.tar.gz ${BASE_URL}/apache-maven-${MAVEN_VERSION}-bin.tar.gz \
  && echo "${SHA}  /tmp/apache-maven.tar.gz" | sha512sum -c - \
  && tar -xzf /tmp/apache-maven.tar.gz -C /usr/share/maven --strip-components=1 \
  && rm -f /tmp/apache-maven.tar.gz \
  && ln -s /usr/share/maven/bin/mvn /usr/bin/mvn

ENV MAVEN_HOME /usr/share/maven
ENV MAVEN_CONFIG "$USER_HOME_DIR/.m2"
