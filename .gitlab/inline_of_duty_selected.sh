#!/usr/bin/env bash
# Return success only for test shards selected for JIT capture.
set -euo pipefail

[[ "${INLINE_OF_DUTY:-false}" == true ]] || exit 1
case "${testJvm:-}" in
  ibm*|semeru*) exit 1 ;;
esac
case "${INLINE_OF_DUTY_SCOPE:-representative}" in
  full) exit 0 ;;
  representative)
    case "${GRADLE_TARGET:-}:${testJvm:-}" in
      ':baseTest:25'|':instrumentationTest:25') exit 0 ;;
      *) exit 1 ;;
    esac
    ;;
  *)
    echo "Invalid INLINE_OF_DUTY_SCOPE; use representative or full" >&2
    exit 2
    ;;
esac
