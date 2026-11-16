#!/usr/bin/env bash
# Writes supported_versions.json for the one-pipeline generate-supported-versions job, which uploads it to S3.
set -euo pipefail

# CI moves Gradle build directories, so ask for the file at the repository root directly.
# shellcheck disable=SC2086 # GRADLE_ARGS holds several arguments
./gradlew :dd-java-agent:instrumentation:aggregateSupportedVersions -PsupportedVersionsFile="$PWD/supported_versions.json" ${GRADLE_ARGS:-}
