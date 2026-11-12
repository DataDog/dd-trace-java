#!/usr/bin/env bash
# Writes supported_versions.json for the one-pipeline generate-supported-versions job, which uploads it to S3.
set -euo pipefail

# shellcheck disable=SC2086 # GRADLE_ARGS holds several arguments
./gradlew :dd-java-agent:instrumentation:aggregateSupportedVersions ${GRADLE_ARGS:-}
cp dd-java-agent/instrumentation/build/supported-versions/supported_versions.json supported_versions.json
