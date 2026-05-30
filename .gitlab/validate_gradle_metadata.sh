#!/usr/bin/env bash

set -euo pipefail

java_bin="${JAVA_25_HOME:-}"
if [[ -n "$java_bin" ]]; then
  java_bin="$java_bin/bin/java"
else
  java_bin="java"
fi

if [[ ! -x "$java_bin" && "$java_bin" != "java" ]]; then
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

set +e
"$java_bin" --class-path "$gradle_lib/*" \
  "$script_dir/gradle-cache/ValidateGradleMetadata.java" "$@"
status=$?
set -e

# The Java program uses 42 for damaged metadata so Java source-launcher failures, which commonly
# exit 1 before main() runs, are treated as validator-unavailable instead of cache corruption.
case "$status" in
  0)
    exit 0
    ;;
  42)
    exit 1
    ;;
  2)
    exit 2
    ;;
  *)
    exit 2
    ;;
esac
