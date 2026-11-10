#!/usr/bin/env bash
# Compare committed bytes in AGENTS.md, .claude/CLAUDE.md, and relative @imports,
# counting each file once. CI runs this in "llm validation" before LLM evaluation,
# comparing HEAD with its merge base with master.
# Results go to the job log and build/llm-validation/context-growth.md (CI artifact).
# This measures file size, not token usage or instruction quality.
# Size changes never fail the job. An incomplete comparison fails the job,
# but the job is optional for merging during testing.
set -euo pipefail
export LC_ALL=C

base_ref=""
candidate_ref=HEAD
report_path=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --base-ref|--candidate-ref|--report)
      [[ $# -ge 2 ]] || { echo "Missing value for $1" >&2; exit 2; }
      case "$1" in
        --base-ref) base_ref="$2" ;;
        --candidate-ref) candidate_ref="$2" ;;
        --report) report_path="$2" ;;
      esac
      shift 2
      ;;
    *) echo "Usage: bash $0 --base-ref <ref> [--candidate-ref <ref>] [--report <file>]" >&2; exit 2 ;;
  esac
done
[[ -n "$base_ref" ]] || { echo "--base-ref is required" >&2; exit 2; }

work_dir="$(mktemp -d)"
trap 'rm -rf -- "$work_dir"' EXIT

# Keep the small Markdown import reader with its only caller.
imports() {
  awk -v source="$1" '
# Extract relative @imports outside fenced blocks and inline code. Requires only POSIX awk.
function invalid(message) {
  print "INCOMPLETE: " message " in " source > "/dev/stderr"
  exit 2
}

function resolve(target, count, segments, stack, depth, component, result, position, directory) {
  if (target ~ /^[\/~]/ || target ~ /[\\:[:cntrl:]]/)
    invalid("External or unsupported @import: " target)
  directory = source
  if (directory ~ /\//) sub(/[^\/]*$/, "", directory)
  else directory = ""
  count = split(directory target, segments, "/")
  depth = 0
  for (position = 1; position <= count; position++) {
    component = segments[position]
    if (component == ".git") invalid("@import references Git metadata: " target)
    if (component == "" || component == ".") continue
    if (component == "..") {
      if (depth == 0) invalid("@import escapes the repository: " target)
      depth--
    } else stack[++depth] = component
  }
  if (depth == 0) invalid("@import must name a file: " target)
  result = stack[1]
  for (position = 2; position <= depth; position++) result = result "/" stack[position]
  print result
}

{
  line = $0
  opening = line
  sub(/^[[:space:]]*/, "", opening)
  if (match(opening, /^(```+|~~~+)/)) {
    marker = substr(opening, 1, RLENGTH)
    if (fence == "") fence = marker
    else if (substr(marker, 1, 1) == substr(fence, 1, 1) && length(marker) >= length(fence)) fence = ""
    next
  }
  if (fence != "") next
  position = 1
  while (position <= length(line)) {
    character = substr(line, position, 1)
    if (character == "`") {
      match(substr(line, position), /^`+/)
      delimiter = substr(line, position, RLENGTH)
      closing = index(substr(line, position + length(delimiter)), delimiter)
      if (closing > 0) {
        position += length(delimiter) + closing - 1 + length(delimiter)
        continue
      }
    }
    if (character != "@" || (position > 1 && substr(line, position - 1, 1) ~ /[[:alnum:]_@]/)) {
      position++
      continue
    }
    position++
    quote = substr(line, position, 1)
    if (quote == "\"" || quote == "\047") {
      closing = index(substr(line, position + 1), quote)
      if (closing == 0) invalid("Unclosed quoted @import")
      target = substr(line, position + 1, closing - 1)
      position += closing + 1
    } else {
      if (!match(substr(line, position), /^[^[:space:]`<>]+/)) continue
      target = substr(line, position, RLENGTH)
      position += RLENGTH
    }
    resolve(target)
  }
}
  ' "$2"
}

visit() {
  local revision="$1" path="$2" required="$3" inventory="$4" seen="$5"
  local mode contents import_list bytes imported
  if grep -Fxq -- "$path" "$seen"; then return 0; fi
  mode="$(git --literal-pathspecs ls-tree --full-tree "$revision" -- "$path" | cut -d ' ' -f 1)" || return 2
  if [[ -z "$mode" ]]; then
    if [[ "$required" == true ]]; then
      echo "Required @import is missing: $path" >&2
      return 2
    fi
    return 0
  fi
  case "$mode" in
    100644|100755) ;;
    *) echo "Instruction path must be a regular file (no symlinks): $path" >&2; return 2 ;;
  esac
  printf '%s\n' "$path" >> "$seen" || return 2
  contents="$(mktemp "$work_dir/contents.XXXXXX")" || return 2
  import_list="$(mktemp "$work_dir/imports.XXXXXX")" || return 2
  git show "$revision:$path" > "$contents" || return 2
  bytes="$(wc -c < "$contents")" || return 2
  printf '%s\t%d\n' "$path" "$bytes" >> "$inventory" || return 2
  imports "$path" "$contents" > "$import_list" || return 2
  while IFS= read -r imported; do
    visit "$revision" "$imported" true "$inventory" "$seen" || return 2
  done < "$import_list"
}

collect() {
  local revision="$1" inventory="$2" seen
  seen="$(mktemp "$work_dir/seen.XXXXXX")" || return 2
  : > "$inventory" || return 2
  visit "$revision" AGENTS.md false "$inventory" "$seen" || return 2
  visit "$revision" .claude/CLAUDE.md false "$inventory" "$seen" || return 2
}

render() {
  printf '# Project instruction text growth\n\nBaseline: `%s`. Candidate: `%s`.\n\n' "$base_revision" "$candidate_revision"
  printf '%s\n' \
    'Counts committed AGENTS.md, .claude/CLAUDE.md, and their relative @imports once.' \
    'This is file size in bytes, not total prompt tokens, runtime usage, or proof of harmful bloat.' ''
  awk -F '\t' '
    FILENAME == ARGV[1] { before[$1]=$2; paths[$1]=1; next }
    { after[$1]=$2; paths[$1]=1 }
    END { for (path in paths) printf "%s\t%d\t%d\n", path, before[path], after[path] }
  ' "$work_dir/baseline" "$work_dir/candidate" | sort | awk -F '\t' '
    BEGIN {
      print "| File | Baseline bytes | Candidate bytes | Change |"
      print "|---|---:|---:|---:|"
    }
    {
      path=$1; gsub(/\|/, "\\|", path)
      printf "| `%s` | %d | %d | %+d |\n", path, $2, $3, $3-$2
      before += $2; after += $3
    }
    END { printf "| **Total** | %d | %d | **%+d** |\n", before, after, after-before }
  ' || return 2
  printf '\n%s\n%s\n' \
    'Advisory: review any growth for necessity, duplication, and placement. No size budget is enforced.' \
    'Use the instruction-review checklist in .llm-validation/README.md alongside the behavior report.'
}

compare() {
  base_revision="$(git rev-parse --verify --end-of-options "$base_ref^{commit}")" || return 2
  candidate_revision="$(git rev-parse --verify --end-of-options "$candidate_ref^{commit}")" || return 2
  collect "$base_revision" "$work_dir/baseline" || return 2
  collect "$candidate_revision" "$work_dir/candidate" || return 2
  render || return 2
}

status=0
if ! compare > "$work_dir/report.md" 2> "$work_dir/error"; then
  status=2
  {
    printf '# Project instruction text growth\n\nINCOMPLETE: '
    cat "$work_dir/error"
  } > "$work_dir/report.md"
fi
if [[ -n "$report_path" ]]; then
  mkdir -p "$(dirname "$report_path")"
  cp "$work_dir/report.md" "$report_path"
fi
cat "$work_dir/report.md"
exit "$status"
