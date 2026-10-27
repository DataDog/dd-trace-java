#!/usr/bin/env bash
# Transfer only JIT captures between test jobs and the pipeline report job.
set -euo pipefail

: "${CI_PIPELINE_ID:?}" "${TEST_COUNTS_S3_BUCKET:?}"
[[ "$CI_PIPELINE_ID" =~ ^[0-9]+$ ]] || { echo "Invalid pipeline ID" >&2; exit 1; }

capture_root="workspace/build/inline-of-duty/captures"
bucket_prefix="s3://${TEST_COUNTS_S3_BUCKET}/jit-inline-captures/${CI_PIPELINE_ID}"

case "${1:-}" in
  upload)
    : "${CI_JOB_ID:?}" "${CI_JOB_NAME:?}"
    [[ "$CI_JOB_ID" =~ ^[0-9]+$ ]] || { echo "Invalid job ID" >&2; exit 1; }
    identity=$(printf '%s' "$CI_JOB_NAME" | sha256sum | cut -c 1-32)
    if [[ ! -d "$capture_root/$CI_PIPELINE_ID/$CI_JOB_ID" ]]; then
      mkdir -p "$capture_root/$CI_PIPELINE_ID/$CI_JOB_ID"
      echo "JIT Inlining Report: no captures for job $CI_JOB_ID"
    fi
    archive=$(mktemp)
    trap 'rm -f "$archive"' EXIT
    tar -C "$capture_root" -czf "$archive" "$CI_PIPELINE_ID/$CI_JOB_ID"
    aws s3 cp "$archive" "$bucket_prefix/$identity.tar.gz" --only-show-errors
    echo "JIT Inlining Report: uploaded captures for job $CI_JOB_ID"
    ;;
  download)
    mkdir -p "$capture_root/$CI_PIPELINE_ID"
    staging=$(mktemp -d)
    trap 'rm -rf "$staging"' EXIT
    aws s3 cp "$bucket_prefix/" "$staging/" --recursive --only-show-errors
    shopt -s nullglob
    archives=("$staging"/*.tar.gz)
    for archive in "${archives[@]}"; do
      tar -C "$capture_root" --no-same-owner -xzf "$archive"
      rm -f "$archive"
    done
    echo "JIT Inlining Report: downloaded ${#archives[@]} capture archive(s)"
    ;;
  *)
    echo "Usage: $0 upload|download" >&2
    exit 2
    ;;
esac
