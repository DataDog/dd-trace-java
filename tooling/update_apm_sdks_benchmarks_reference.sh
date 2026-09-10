#!/usr/bin/env bash
set -euo pipefail

# This script updates the shared SHA used by the apm-sdks-benchmarks includes
# and runtime clone in GitLab CI.
# Usage: tooling/update_apm_sdks_benchmarks_reference.sh <commit-sha>

if [[ $# -ne 1 || ! "$1" =~ ^[0-9a-f]{40}$ ]]; then
    echo "Usage: $0 <40-character commit SHA>" >&2
    exit 1
fi

REF="$1"
GITLAB_TARGET=".gitlab-ci.yml"
PROJECT="DataDog/apm-reliability/apm-sdks-benchmarks"
REFERENCE_PATTERN="(  - project: '$PROJECT'\n    file: '[^']+'\n    ref: &apm_sdks_benchmarks_sha ')(?:main|[0-9a-f]{40})(')"
SHA_PATTERN='  APM_SDKS_BENCHMARKS_SHA: \*apm_sdks_benchmarks_sha'

if [[ ! -f "$GITLAB_TARGET" ]]; then
    echo "Error: Target file $GITLAB_TARGET does not exist" >&2
    exit 1
fi

REFERENCE_COUNT=$(REFERENCE_PATTERN="$REFERENCE_PATTERN" perl -0ne '
    $count++ while /$ENV{REFERENCE_PATTERN}/g;
    END { print $count // 0 }
' "$GITLAB_TARGET")
SHA_COUNT=$(SHA_PATTERN="$SHA_PATTERN" perl -0ne '
    $count++ while /$ENV{SHA_PATTERN}/g;
    END { print $count // 0 }
' "$GITLAB_TARGET")

if [[ "$REFERENCE_COUNT" -ne 1 ]]; then
    echo "Error: Expected one anchored $PROJECT reference, found $REFERENCE_COUNT" >&2
    exit 1
fi
if [[ "$SHA_COUNT" -ne 1 ]]; then
    echo "Error: Expected APM_SDKS_BENCHMARKS_SHA to use the shared anchor" >&2
    exit 1
fi

REFERENCE_PATTERN="$REFERENCE_PATTERN" REF="$REF" perl -0pi -e '
    s/$ENV{REFERENCE_PATTERN}/${1}$ENV{REF}${2}/g
' "$GITLAB_TARGET"
echo "Updated the shared apm-sdks-benchmarks SHA to $REF"
