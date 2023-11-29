FROM gradle:jdk11


COPY . /app
RUN mv /app/build.gradle.standalone /app/build.gradle

WORKDIR /app
RUN gradle shadowJar

ADD https://output.circle-artifacts.com/output/job/660f37dc-79bd-4209-a380-7af8aaca023d/artifacts/0/libs/dd-java-agent-1.25.0-SNAPSHOT.jar /app/dd-java-agent.jar
ENV DD_APPSEC_ENABLED=true
ENV DD_SERVICE=valentin-02
ENV DD_REMOTE_CONFIG_ENABLED=false
ENV DD_EXPERIMENTAL_API_SECURITY_ENABLED=true
ENV DD_API_SECURITY_REQUEST_SAMPLE_RATE=1.00

CMD ["java", "-javaagent:/app/dd-java-agent.jar", "-Ddatadog.slf4j.simpleLogger.defaultLogLevel=debug", "-Ddatadog.slf4j.simpleLogger.logFile=/app/dd.log", "-jar", "/app/build/libs/app-all.jar"]