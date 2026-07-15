#!/usr/bin/env bash
#
# Clone + build Spring PetClinic at a pinned commit, and print the path to the runnable fat jar.
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

log() { echo "[fetch-petclinic] $*" >&2; }

# Reuse an existing build if present.
existing_jar=$(ls "$src_dir"/target/spring-petclinic-*.jar 2>/dev/null \
  | grep -v -- '-sources.jar$' | grep -v -- '.original$' | head -1 || true)
if [ -n "$existing_jar" ]; then
  log "reusing existing jar: $existing_jar"
  echo "$existing_jar"
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

jar=$(ls "$src_dir"/target/spring-petclinic-*.jar 2>/dev/null \
  | grep -v -- '-sources.jar$' | grep -v -- '.original$' | head -1 || true)
if [ -z "$jar" ]; then
  log "ERROR: build finished but no runnable jar found under $src_dir/target"
  exit 1
fi
log "built jar: $jar"
echo "$jar"
