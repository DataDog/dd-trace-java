#!/usr/bin/env bash
# Pre-push review entry point for dd-trace-java.
#
# Runs the coding harness's built-in code review over the branch changes, then the
# repo-specific review extensions (currently /perf-review). Review expectations live
# in AGENTS.md (see "Review Guidelines"). Advisory only: findings are printed and the
# script exits 0 regardless of what was found.
set -euo pipefail

usage() {
    cat <<EOF
Usage: ./review.sh [options]

Review the changes on the current branch before pushing.

Options:
  --base <ref>        Compare against <ref> (default: merge-base with upstream master)
  --harness <name>    Harness to run: claude, codex (default: first one found on PATH)
  --no-extensions     Only run the built-in review, skip repo extensions (/perf-review)
  --out <file>        Also write the review output to <file>
  -h, --help          Show this help
EOF
}

BASE=""
HARNESS=""
RUN_EXTENSIONS=true
OUT=""

require_value() {
    if [ $# -lt 2 ] || [ -z "$2" ]; then
        echo "❌ Missing value for $1" >&2
        usage >&2
        exit 1
    fi
}

while [ $# -gt 0 ]; do
    case "$1" in
        --base) require_value "$@"; BASE="$2"; shift 2 ;;
        --harness) require_value "$@"; HARNESS="$2"; shift 2 ;;
        --no-extensions) RUN_EXTENSIONS=false; shift ;;
        --out) require_value "$@"; OUT="$2"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "❌ Unknown option: $1" >&2; usage >&2; exit 1 ;;
    esac
done

cd "$(git rev-parse --show-toplevel)"

find_base() {
    local upstream
    upstream=$(git remote -v | grep -E 'DataDog/[^/]+(\.git)?\s' | head -1 | awk '{print $1}')
    git merge-base HEAD "${upstream:-origin}/master"
}

detect_harness() {
    for candidate in claude codex; do
        if command -v "$candidate" &> /dev/null; then
            echo "$candidate"
            return
        fi
    done
    echo "❌ No supported harness found on PATH (claude, codex)." >&2
    exit 1
}

if [ -z "$BASE" ]; then
    BASE=$(find_base)
else
    BASE=$(git merge-base HEAD "$BASE")
fi
case "${HARNESS:=$(detect_harness)}" in
    claude|codex) ;;
    *) echo "❌ Unsupported harness: $HARNESS (supported: claude, codex)" >&2; exit 1 ;;
esac

if git diff --quiet "$BASE" HEAD; then
    echo "No changes since $BASE; nothing to review."
    exit 0
fi

echo "🔎 Reviewing changes since $(git rev-parse --short "$BASE") with $HARNESS"
git diff --stat "$BASE" HEAD
echo

REVIEW_PROMPT="Review the changes on this branch since $BASE (git diff $BASE...HEAD) \
for correctness bugs. Apply the Review Guidelines and Critical constraints in AGENTS.md \
and the docs they link. Report only findings you are confident about, with \
file:line references. Do not edit any files."
PERF_PROMPT="/perf-review the changes on this branch since $BASE"

# Read-only tool set for headless runs, so nothing stalls on a permission prompt.
CLAUDE_ALLOWED_TOOLS="Read Grep Glob Agent Skill Bash(git diff:*) Bash(git log:*) Bash(git show:*) Bash(git merge-base:*) Bash(git remote:*) Bash(git rev-parse:*) Bash(git status:*) Bash(git blame:*) Bash(grep:*) Bash(find:*) Bash(ls:*) Bash(cat:*) Bash(head:*) Bash(sed -n:*) Bash(wc:*)"

run_harness() {
    local prompt="$1"
    case "$HARNESS" in
        claude)
            claude -p "$prompt" \
                --permission-mode dontAsk \
                --allowedTools "$CLAUDE_ALLOWED_TOOLS" \
                --disallowedTools "Edit Write NotebookEdit"
            ;;
        codex)
            codex exec --sandbox read-only "$prompt"
            ;;
    esac
}

run_reviews() {
    echo "## Code review"
    echo
    run_harness "$REVIEW_PROMPT" || echo "⚠️ Code review failed"
    if [ "$RUN_EXTENSIONS" = true ]; then
        echo
        echo "## Performance review"
        echo
        if [ "$HARNESS" = claude ]; then
            run_harness "$PERF_PROMPT" || echo "⚠️ Performance review failed"
        else
            run_harness "Follow .agents/skills/perf-review/SKILL.md to review the changes on this branch since $BASE" \
                || echo "⚠️ Performance review failed"
        fi
    fi
}

if [ -n "$OUT" ]; then
    run_reviews | tee "$OUT"
else
    run_reviews
fi
