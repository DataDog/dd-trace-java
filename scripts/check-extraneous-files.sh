#!/usr/bin/env bash
set -euo pipefail

BASE_BRANCH="master"

usage() {
  echo "Usage: $(basename "$0") [--base <branch>] [--help]"
  echo ""
  echo "Checks for extraneous files that should not be committed to the repository."
  echo "Compares new files added against the base branch."
  echo ""
  echo "Options:"
  echo "  --base <branch>   Base branch to compare against (default: master)"
  echo "  --help            Print this usage message"
  echo ""
  echo "Blocked patterns:"
  echo "  *_REPORT.md or *_REFERENCE.md at any level (AI-generated reports)"
  echo "  Top-level *.sh scripts not in scripts/, gradle/, or .gitlab/ directories"
  echo "  Files named QUICK_REFERENCE* or CHEATSHEET*"
  echo "  run-*.sh scripts at any level (ad-hoc test runners)"
  exit 0
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --base)
      BASE_BRANCH="$2"
      shift 2
      ;;
    --help)
      usage
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage
      ;;
  esac
done

violations=0

while IFS= read -r file; do
  reason=""

  # Pattern 1: *_REPORT.md or *_REFERENCE.md at any level
  basename_file="$(basename "$file")"
  if [[ "$basename_file" == *_REPORT.md || "$basename_file" == *_REFERENCE.md ]]; then
    reason="AI-generated report/reference file"
  fi

  # Pattern 2: Top-level *.sh scripts not in scripts/, gradle/, or .gitlab/
  if [[ -z "$reason" && "$file" == *.sh ]]; then
    dir="$(dirname "$file")"
    if [[ "$dir" == "." ]]; then
      reason="Top-level shell script (move to scripts/, gradle/, or .gitlab/)"
    fi
  fi

  # Pattern 3: Files named QUICK_REFERENCE* or CHEATSHEET*
  if [[ -z "$reason" ]]; then
    if [[ "$basename_file" == QUICK_REFERENCE* || "$basename_file" == CHEATSHEET* ]]; then
      reason="Quick reference or cheatsheet file"
    fi
  fi

  # Pattern 4: run-*.sh scripts at any level
  if [[ -z "$reason" && "$basename_file" == run-*.sh ]]; then
    reason="Ad-hoc test runner script (run-*.sh)"
  fi

  if [[ -n "$reason" ]]; then
    echo "BLOCKED: $file — $reason"
    violations=$((violations + 1))
  fi
done < <(git diff --name-only --diff-filter=A "${BASE_BRANCH}...HEAD" 2>/dev/null || git diff --name-only --diff-filter=A "${BASE_BRANCH}" 2>/dev/null)

if [[ $violations -gt 0 ]]; then
  exit 1
fi

exit 0
