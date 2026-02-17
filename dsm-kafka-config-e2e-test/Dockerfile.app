FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src/ src/
RUN mvn package -q -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/dsm-kafka-config-test-1.0-SNAPSHOT.jar app.jar
# The dd-java-agent jar will be mounted via docker-compose volume
ENTRYPOINT ["java", "-javaagent:/agent/dd-java-agent.jar", "-jar", "app.jar"]
