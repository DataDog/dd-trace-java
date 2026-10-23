#!/usr/bin/env bash
# Counts the lines added from the merge base of two revisions, excluding moved lines and lock files.
# Usage: count-added-lines.sh <base> [head]
#
# Moved lines (blocks of at least 20 alphanumeric characters deleted and added elsewhere, even
# re-indented or in another file) are told apart from added lines using diff colors:
# added lines are painted green and moved lines blue, then only the green lines are counted.
set -euo pipefail

BASE=${1:?Usage: $0 <base> [head]}
HEAD=${2:-HEAD}

git \
  -c color.diff.meta=bold \
  -c color.diff.new=green \
  -c color.diff.newMoved=blue \
  diff \
  --color=always \
  --color-moved=blocks \
  --color-moved-ws=allow-indentation-change \
  --ws-error-highlight=none \
  --merge-base \
  --find-renames \
  --no-ext-diff \
  "$BASE" "$HEAD" \
  -- . ':(exclude,glob)**/gradle.lockfile' |
  awk '/^\033\[32m\+/ { count++ } END { print count + 0 }'
