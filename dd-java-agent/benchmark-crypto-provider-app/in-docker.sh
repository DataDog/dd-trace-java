#!/usr/bin/env bash
# Runs a command from this directory inside a Linux JDK 21 container. See README.md for examples.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
IMAGE="${BENCH_IMAGE:-eclipse-temurin:21-jdk}"
PLATFORM="${BENCH_PLATFORM:-linux/arm64}"
TTY=()
[[ -t 1 ]] && TTY=(-t)
# shellcheck disable=SC2086
exec docker run --rm "${TTY[@]}" --platform "$PLATFORM" ${DOCKER_OPTS:-} \
  -v "$REPO_ROOT:$REPO_ROOT" -w "$HERE" \
  -e BENCH_JAVA_HOME=/opt/java/openjdk \
  -e BENCH_AGENT_COMMIT="$(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || echo ?)" \
  "$IMAGE" "$@"
