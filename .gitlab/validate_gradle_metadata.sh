#!/usr/bin/env bash

set -euo pipefail

java_home="${JAVA_25_HOME:-}"
if [[ -n "$java_home" ]]; then
  java_bin="$java_home/bin/java"
  javac_bin="$java_home/bin/javac"
else
  java_bin="java"
  javac_bin="javac"
fi

if [[ "$java_bin" != "java" && ! -x "$java_bin" ]]; then
  echo "Gradle metadata validator could not find Java executable: $java_bin" >&2
  exit 2
fi

script_dir="$(cd "$(dirname "$0")" && pwd)"
gradle_version="${GRADLE_VERSION:-}"
if [[ -z "$gradle_version" && -f gradle/wrapper/gradle-wrapper.properties ]]; then
  gradle_version="$(
    sed -nE 's#^distributionUrl=.*gradle-([0-9][^-]*)-(bin|all)\.zip$#\1#p' \
      gradle/wrapper/gradle-wrapper.properties \
      | head -n 1
  )"
fi

if [[ -z "$gradle_version" ]]; then
  echo "Gradle metadata validator could not determine Gradle version" >&2
  exit 2
fi

gradle_lib=""
for gradle_home in "${GRADLE_USER_HOME:-$PWD/.gradle}" "$HOME/.gradle"; do
  [[ -d "$gradle_home/wrapper/dists" ]] || continue
  gradle_lib="$(
    find "$gradle_home/wrapper/dists" \
      -path "*/gradle-${gradle_version}/lib" \
      -type d \
      -print \
      -quit 2>/dev/null
  )"
  if [[ -n "$gradle_lib" ]]; then
    break
  fi
done

if [[ -z "$gradle_lib" ]]; then
  echo \
    "Gradle metadata validator could not find Gradle $gradle_version distribution lib directory" \
    >&2
  exit 2
fi

# Pre-compile with -proc:none rather than using the `java <file>.java` source launcher: the launcher
# would otherwise discover and run annotation processors bundled in the Gradle jars on the classpath.
build_dir="$(mktemp -d)"
trap 'rm -rf "$build_dir"' EXIT

if ! "$javac_bin" -proc:none -classpath "$gradle_lib/*" -d "$build_dir" \
    "$script_dir/gradle-cache/ValidateGradleMetadata.java"; then
  echo "Gradle metadata validator could not compile ValidateGradleMetadata" >&2
  exit 2
fi

set +e
"$java_bin" -classpath "$build_dir:$gradle_lib/*" ValidateGradleMetadata "$@"
status=$?
set -e

# ValidateGradleMetadata exits 65 (EX_DATAERR) for damaged metadata so that a JVM that exits 1
# before main() runs is treated as validator-unavailable instead of as cache corruption.
case "$status" in
  0) exit 0 ;;
  65) exit 1 ;;
  *) exit 2 ;;
esac
