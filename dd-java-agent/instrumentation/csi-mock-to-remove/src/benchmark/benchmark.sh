#!/bin/bash
set -eu

command -v docker-compose >/dev/null 2>&1 || {
  echo >&2 "Install docker and docker-compose before running the benchmark. Aborting."
  exit 1
}

command -v k6 >/dev/null 2>&1 || {
  echo >&2 "Install k6 before running the benchmark. Aborting."
  exit 1
}

mkdir -p logs

run_benchmark() {
  export JAVA_OPTS="-Xmx8G -Djava.security.egd=file:/dev/./urandom"
  if [ "$1" = "datadog" ]; then
    echo "Running application with datadog"
    export DD_IAST_ENABLED=false
    export JAVA_OPTS="-javaagent:/dd-java-agent.jar $JAVA_OPTS"
  elif [ "$1" = "datadog-csi" ]; then
    echo "Running application with datadog plus CSI"
    export DD_IAST_ENABLED=true
    export JAVA_OPTS="-javaagent:/dd-java-agent.jar $JAVA_OPTS"
  else
    echo "Running application without library"
  fi

  docker-compose up -d app >/dev/null 2>&1
  timeout 300 bash -c 'while [[ "$(curl -s -o /dev/null -w ''%{http_code}'' http://localhost:8080)" != "200" ]]; do sleep 5; done' || false

  echo "Running benchmark."
  AGENT=$1 k6 run benchmark.js --out json=logs/$1.json.log

  echo "Cleaning up."
  docker logs benchmark-app >logs/$1.log 2>&1
  docker-compose stop app >/dev/null 2>&1
  docker-compose rm --force app >/dev/null 2>&1
}

# Remove datadog agent and start fresh
echo "Starting datadog agent."
rm *.log >/dev/null 2>&1 || true
docker-compose down >/dev/null 2>&1
docker-compose up -d datadog-agent >/dev/null 2>&1
sleep 10

run_benchmark "none"
run_benchmark "datadog"
run_benchmark "datadog-csi"

echo "Stopping datadog agent."
docker-compose stop datadog-agent >/dev/null 2>&1
docker-compose rm --force datadog-agent >/dev/null 2>&1


