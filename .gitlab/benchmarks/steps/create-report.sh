#!/usr/bin/env bash

set -x

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]:-$0}")" &>/dev/null && pwd 2>/dev/null)"
export SCRIPT_DIR

benchmark_analyzer compare pairwise \
  --baseline='{"baseline_or_candidate":"baseline"}' \
  --candidate='{"baseline_or_candidate":"candidate"}' \
  --format=md \
  --metrics="[ \
    'agg_http_req_duration_min', \
    'agg_http_req_duration_p50', \
    'agg_http_req_duration_p75', \
    'agg_http_req_duration_p99', \
    'throughput' \
  ]" \
  --outpath="${ARTIFACTS_DIR}/comparison-baseline-vs-candidate.md" \
  "${ARTIFACTS_DIR}/petclinic-"{baseline,candidate}.converted.json

"${SCRIPT_DIR}/append-report" "${ARTIFACTS_DIR}" >>"${ARTIFACTS_DIR}/comparison-baseline-vs-candidate.md"
