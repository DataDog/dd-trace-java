#!/usr/bin/env bash
set -eu

# instrumentation
IMAGES=(
  aerospike:5.5.0.9
  cassandra:3
  cassandra:4
  couchbase/server:7.1.0
  memcached:1.6.14-alpine
  mysql:8.0
  postgres:11.1
  rabbitmq:3.9.20-alpine
)
# smoke-tests
IMAGES+=(
  mongo:4.0.10
  #rabbitmq:3.9.20-alpine
)

echo "Waiting for Docker to be available"
while ! docker system info &>/dev/null; do
  sleep 1
done

echo "Docker is avaiable now, pulling images"
for image in "${IMAGES[@]}"; do
  docker pull "${image}" || true
done
