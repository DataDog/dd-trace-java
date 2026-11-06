#!/usr/bin/env bash
# Transfer compact JIT facts between test jobs and the pipeline report job.
set -euo pipefail

: "${CI_PIPELINE_ID:?}" "${TEST_COUNTS_S3_BUCKET:?}"
[[ "$CI_PIPELINE_ID" =~ ^[0-9]+$ ]] || { echo "Invalid pipeline ID" >&2; exit 1; }

facts_root="workspace/build/inline-of-duty/facts/${CI_PIPELINE_ID}"
bucket_prefix="s3://${TEST_COUNTS_S3_BUCKET}/jit-inline-facts/${CI_PIPELINE_ID}"

case "${1:-}" in
  upload)
    : "${CI_JOB_ID:?}" "${CI_JOB_NAME:?}"
    [[ "$CI_JOB_ID" =~ ^[0-9]+$ ]] || { echo "Invalid job ID" >&2; exit 1; }
    identity=$(printf '%s' "$CI_JOB_NAME" | sha256sum | cut -c 1-32)
    facts="workspace/build/reports/inline-of-duty/aggregation.json"
    if [[ ! -s "$facts" ]]; then
      facts=$(mktemp)
      trap 'rm -f "$facts"' EXIT
      printf '{"schema_version":1,"missing":true,"source_prefix":"%s/missing","capture_errors":["No JIT aggregation facts produced by job %s"]}\n' "$CI_JOB_ID" "$CI_JOB_ID" > "$facts"
      echo "JIT Inlining Report: no aggregation facts for job $CI_JOB_ID"
    fi
    aws s3 cp "$facts" "$bucket_prefix/$identity.json" --only-show-errors
    echo "JIT Inlining Report: uploaded facts for job $CI_JOB_ID"
    ;;
  download)
    mkdir -p "$facts_root"
    aws s3 cp "$bucket_prefix/" "$facts_root/" --recursive --only-show-errors
    shopt -s nullglob
    facts=("$facts_root"/*.json)
    echo "JIT Inlining Report: downloaded ${#facts[@]} aggregation fact file(s)"
    ;;
  *)
    echo "Usage: $0 upload|download" >&2
    exit 2
    ;;
esac
