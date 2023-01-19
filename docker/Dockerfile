FROM eclipse-temurin:8 as agent

# Install required bsdtar
RUN apt-get update && \
	apt-get install -y libarchive-tools
# Install tracer
COPY ./dd-java-agent.jar /dd-tracer/

COPY ./library_version.sh .

RUN sh library_version.sh 