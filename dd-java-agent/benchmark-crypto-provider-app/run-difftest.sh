#!/usr/bin/env bash
# Runs the JDK-vs-ACCP differential tests (JCA outputs + TLS interop with a JDK-only peer JVM) with
# the JUnit console launcher. Build first with ./build.sh. In Linux: ./in-docker.sh ./run-difftest.sh
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/bench-env.sh"
TB="$HERE/build/testbundle"
require_built "$TB/console" "$ACCP_JAR"
echo "== $("$JAVA" -version 2>&1 | head -1), $(uname -sm), ACCP $(basename "$ACCP_JAR")"
CP="$TB/classes:$TB/lib/*:$ACCP_JAR"
exec "$JAVA" "-Dbench.test.classpath=$CP" -jar "$TB"/console/junit-platform-console-standalone-*.jar execute \
  --class-path "$CP" \
  --select-package com.example.cryptobench.difftest \
  --details tree --disable-banner "$@"
