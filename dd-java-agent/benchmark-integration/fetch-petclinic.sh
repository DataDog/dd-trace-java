#!/usr/bin/env bash
#
# Clone + build Spring PetClinic at a pinned commit, extract the fat jar, and print the path to the
# extracted application jar (which runs with `java -jar`, its dependencies on a plain class path).
#
# Why extracted: run as a fat jar, PetClinic serializes requests on Spring Boot's nested-jar loader.
# See the "Running PetClinic without a class loader lock" section of README.md.
#
# Idempotent: if the jar already exists it is reused (no clone, no rebuild). All progress output
# goes to stderr so that stdout is ONLY the jar path -- callers capture it with:
#     petclinic_jar=$(./fetch-petclinic.sh)
#
# Requires: git, a JDK (for the Maven wrapper), and network access on the first run.

set -euo pipefail

# Pinned so results are reproducible across machines and over time. Bump deliberately.
PETCLINIC_REPO="https://github.com/spring-projects/spring-petclinic.git"
PETCLINIC_SHA="51045d1648dad955df586150c1a1a6e22ef400c2"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# build/ is already git-ignored by the module, so the checkout is never committed.
src_dir="$script_dir/build/spring-petclinic"

extract_dir="$script_dir/build/spring-petclinic-extracted"

log() { echo "[fetch-petclinic] $*" >&2; }

# Extract fat jar $1 (Spring Boot's jarmode=tools layout: <name>.jar + lib/) unless the extraction is
# already newer than it, and print the extracted application jar.
extract() {
  local fat="$1" app
  app="$extract_dir/$(basename "$fat")"
  if [ ! -f "$app" ] || [ "$fat" -nt "$app" ]; then
    log "extracting $fat"
    rm -rf "$extract_dir"
    java -Djarmode=tools -jar "$fat" extract --destination "$extract_dir" >&2 \
      || { log "ERROR: could not extract $fat"; exit 1; }
  fi
  [ -f "$app" ] || { log "ERROR: extracted jar not found at $app"; exit 1; }
  echo "$app"
}

# The runnable fat jar under target/, if built (not the sources jar or the pre-repackage original).
built_jar() {
  ls "$src_dir"/target/spring-petclinic-*.jar 2>/dev/null \
    | grep -v -- '-sources.jar$' | grep -v -- '.original$' | head -1 || true
}

# Reuse an existing build only if it was built from the pinned commit; otherwise rebuild, dropping
# the stale jar so it can't be picked up instead of the new one.
existing_jar=$(built_jar)
if [ -n "$existing_jar" ] && [ "$(git -C "$src_dir" rev-parse HEAD 2>/dev/null)" != "$PETCLINIC_SHA" ]; then
  log "existing jar was not built from $PETCLINIC_SHA; rebuilding"
  rm -f "$src_dir"/target/spring-petclinic-*.jar
  existing_jar=""
fi
if [ -n "$existing_jar" ]; then
  log "reusing existing jar: $existing_jar"
  extract "$existing_jar"
  exit 0
fi

# Fetch exactly the pinned commit (shallow) -- avoids pulling the whole history.
if [ ! -d "$src_dir/.git" ]; then
  log "cloning $PETCLINIC_REPO @ $PETCLINIC_SHA"
  mkdir -p "$src_dir"
  git -C "$src_dir" init -q
  git -C "$src_dir" remote add origin "$PETCLINIC_REPO" 2>/dev/null || true
fi
log "fetching pinned commit"
git -C "$src_dir" fetch -q --depth 1 origin "$PETCLINIC_SHA"
git -C "$src_dir" checkout -q FETCH_HEAD

log "building PetClinic (this can take a few minutes on first run)"
( cd "$src_dir" && ./mvnw -q -B -DskipTests package )

jar=$(built_jar)
if [ -z "$jar" ]; then
  log "ERROR: build finished but no runnable jar found under $src_dir/target"
  exit 1
fi
log "built jar: $jar"
extract "$jar"
