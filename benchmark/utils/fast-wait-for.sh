#!/usr/bin/env bash
set -euxo pipefail
host="$1"
port="$2"
cmd="$3"
pid="$(pidof $cmd)"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"
cd "$SCRIPT_DIR"
./fast-wait-for "$host" "$port" "$pid"
