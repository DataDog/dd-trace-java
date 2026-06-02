#!/usr/bin/env bash

set -uo pipefail

gradle_version="${1:?usage: mitigate_corrupted_gradle_cache.sh <gradle-version>}"
script_dir="$(cd "$(dirname "$0")" && pwd)"

java_home="${JAVA_25_HOME:-}"
java_bin="${java_home:+$java_home/bin/}java"
javac_bin="${java_home:+$java_home/bin/}javac"

gradle_lib=""
for gradle_home in "${GRADLE_USER_HOME:-$PWD/.gradle}" "$HOME/.gradle"; do
  [[ -d "$gradle_home/wrapper/dists" ]] || continue
  gradle_lib="$(
    find "$gradle_home/wrapper/dists" -path "*/gradle-${gradle_version}/lib" -type d -print -quit \
      2>/dev/null
  )"
  [[ -n "$gradle_lib" ]] && break
done
if [[ -z "$gradle_lib" ]]; then
  echo "Gradle $gradle_version distribution not found; leaving cache unchanged." >&2
  exit 0
fi

build_dir="$(mktemp -d)"
trap 'rm -rf "$build_dir"' EXIT

# -proc:none keeps the compiler from running annotation processors bundled in the Gradle jars.
if ! "$javac_bin" -proc:none -classpath "$gradle_lib/*" -d "$build_dir" \
    "$script_dir/CorruptedGradleCacheMitigator.java"; then
  echo "Could not compile CorruptedGradleCacheMitigator; leaving cache unchanged." >&2
  exit 0
fi

"$java_bin" -classpath "$build_dir:$gradle_lib/*" \
  CorruptedGradleCacheMitigator "$gradle_version"
