#!/usr/bin/env bash
#
# Verifies the native policy fixture (cases.json) against the pinned shared contract corpus.
# Requires jq.
#
# Usage: verify.sh /path/to/browser-proposal.jsonl
#
set -euo pipefail

readonly SOURCE_SHA256="4c4628ae30217bd2ead2ef34144f43e0163f9916ff0c8accae0847391dff64a6"
readonly EXPECTED_CASES=90

fail() {
  echo "verify.sh: error: $1" >&2
  exit 2
}

if [ $# -ne 1 ]; then
  echo "Usage: $0 /path/to/browser-proposal.jsonl" >&2
  exit 2
fi
readonly SOURCE=$1
readonly CASES="$(cd "$(dirname "$0")" && pwd)/cases.json"

if command -v sha256sum >/dev/null; then
  SOURCE_HASH=$(sha256sum "$SOURCE" | cut -d ' ' -f 1)
else
  SOURCE_HASH=$(shasum -a 256 "$SOURCE" | cut -d ' ' -f 1)
fi
if [ "$SOURCE_HASH" != "$SOURCE_SHA256" ]; then
  fail "source corpus does not match the pinned SHA-256"
fi

# Keep the policy rows in source order, drop kind and decision_ids, and strip the policy/ ID prefix.
EXPECTED=$(jq --compact-output --slurp '
  [.[] | select(.kind == "policy") | del(.kind, .decision_ids) | .id |= ltrimstr("policy/")]
' "$SOURCE")

if [ "$(jq 'length' <<<"$EXPECTED")" -ne "$EXPECTED_CASES" ] ||
  [ "$(jq '[.[].id] | unique | length' <<<"$EXPECTED")" -ne "$EXPECTED_CASES" ]; then
  fail "expected $EXPECTED_CASES unique policy cases"
fi
if [ "$(jq --null-input --argjson expected "$EXPECTED" --slurpfile actual "$CASES" '$actual == [$expected]')" != "true" ]; then
  fail "cases.json differs from the pinned policy extraction"
fi
echo "Verified all $EXPECTED_CASES policy cases against the pinned source"
