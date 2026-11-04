#!/usr/bin/env bash
# Downloads the Apache Kafka release (KAFKA_VERSION, default 3.9.1), verifies its published SHA-512,
# and unpacks it under build/. Reuses an existing unpacked copy.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

if [ -x "$KAFKA_HOME/bin/kafka-server-start.sh" ]; then
  log "reusing $KAFKA_HOME"
  exit 0
fi

archive="kafka_${SCALA_VERSION}-${KAFKA_VERSION}.tgz"
url="https://archive.apache.org/dist/kafka/${KAFKA_VERSION}/${archive}"
download_dir="$WORK_DIR/download"
mkdir -p "$download_dir"

log "downloading $url"
curl -fsSL -o "$download_dir/$archive" "$url"
curl -fsSL -o "$download_dir/$archive.sha512" "$url.sha512"

# The .sha512 file is "<name>: <hex in space-separated groups, possibly wrapped>".
expected="$(cut -d: -f2- "$download_dir/$archive.sha512" | tr -d ' \n\r' | tr 'A-F' 'a-f')"
actual="$(shasum -a 512 "$download_dir/$archive" | cut -d' ' -f1)"
if [ "$expected" != "$actual" ]; then
  log "ERROR: SHA-512 mismatch for $archive"
  exit 1
fi
log "SHA-512 verified"

tar -xzf "$download_dir/$archive" -C "$WORK_DIR"
log "unpacked to $KAFKA_HOME"
