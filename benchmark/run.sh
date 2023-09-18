#!/usr/bin/env bash
set -eu

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"
readonly INITIAL_DIR="$(pwd)"
readonly TRACER="${SCRIPT_DIR}/tracer/dd-java-agent.jar"

cd "${SCRIPT_DIR}"

# Build container image
echo "Building base image ..."
docker build \
  -t dd-trace-java/benchmark \
  .

# Find or rebuild tracer to be used in the benchmarks
if [[ ! -f "${TRACER}" ]]; then
  mkdir -p "${SCRIPT_DIR}/tracer"
  cd "${SCRIPT_DIR}/.."
  readonly TRACER_VERSION=$(./gradlew properties -q | grep "version:" | awk '{print $2}')
  readonly TRACER_COMPILED="${SCRIPT_DIR}/../dd-java-agent/build/libs/dd-java-agent-${TRACER_VERSION}.jar"
  if [ ! -f "${TRACER_COMPILED}" ]; then
    echo "Tracer not found, starting gradle compile ..."
    ./gradlew assemble
  fi
  cp "${TRACER_COMPILED}" "${TRACER}"
  cd "${SCRIPT_DIR}"
fi

# Trigger benchmarks
echo "Running benchmarks ..."
docker run --rm \
  -v "${HOME}/.gradle":/home/benchmark/.gradle:delegated \
  -v "${PWD}/..":/tracer:delegated \
  -w /tracer/benchmark \
  -e GRADLE_OPTS="-Dorg.gradle.daemon=false" \
  --entrypoint=./benchmarks.sh \
  --name dd-trace-java-benchmark \
  --cap-add SYS_ADMIN \
  dd-trace-java/benchmark \
  "$@"

cd "${INITIAL_DIR}"
