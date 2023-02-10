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
t0=$SECONDS
while ! docker system info &>/dev/null; do
  sleep 1
  t1=$SECONDS
  # Give up after one minute. Even if Docker becomes available,
  # prefetching is unlikely to help if it kicks in too late.
  if [[ $((t1 - t0)) -gt 60 ]]; then
    echo "Waiting for Docker timeout, skipping image prefetch."
    exit 0
  fi
done

echo "Docker is avaiable now, pulling images"
for image in "${IMAGES[@]}"; do
  docker pull "${image}" || true
done
