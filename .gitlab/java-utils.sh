#!/usr/bin/env bash

# Use the CI tooling JDK for Java source launchers, independently of the test JVM.
resolve_java() {
  if [[ -n "${JAVA_25_HOME:-}" ]]; then
    printf '%s/bin/java\n' "$JAVA_25_HOME"
  else
    printf '%s\n' java
  fi
}
