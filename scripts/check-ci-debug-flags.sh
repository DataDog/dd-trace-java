#!/usr/bin/env bash
set -euo pipefail

DRY_RUN=false
VIOLATIONS=0

usage() {
  cat <<EOF
Usage: $(basename "$0") [--dry-run] [--help]

Scans git diffs for temporary debug/test configuration values that should not be merged to master.

Options:
  --dry-run   Scan the current working tree diff (git diff HEAD) and report findings without failing
  --help      Print this usage message

Without flags, scans staged changes (git diff --cached) and exits 1 if any patterns found.

Output format: WARNING: <file>:<line>: <description of debug flag>
EOF
}

for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=true ;;
    --help) usage; exit 0 ;;
    *) echo "Unknown argument: $arg" >&2; usage >&2; exit 1 ;;
  esac
done

if $DRY_RUN; then
  DIFF=$(git diff HEAD)
else
  DIFF=$(git diff --cached)
fi

if [[ -z "$DIFF" ]]; then
  exit 0
fi

# Parse unified diff, tracking current file and line numbers
current_file=""
current_line=0

warn() {
  local file="$1"
  local line="$2"
  local msg="$3"
  echo "WARNING: $file:$line: $msg"
  VIOLATIONS=$((VIOLATIONS + 1))
}

# Process diff line by line
while IFS= read -r line; do
  # Track current file from diff header
  if [[ "$line" =~ ^\+\+\+\ b/(.+)$ ]]; then
    current_file="${BASH_REMATCH[1]}"
    current_line=0
    continue
  fi

  # Track line numbers from hunk headers
  if [[ "$line" =~ ^@@\ -[0-9]+(,[0-9]+)?\ \+([0-9]+)(,[0-9]+)?\ @@.* ]]; then
    current_line="${BASH_REMATCH[2]}"
    # Subtract 1 because we'll increment before checking added lines
    current_line=$((current_line - 1))
    continue
  fi

  # Skip removed lines and diff metadata
  if [[ "$line" =~ ^- ]] || [[ "$line" =~ ^(diff|index|---) ]]; then
    continue
  fi

  # Count context and added lines
  if [[ "$line" =~ ^(\+| ) ]]; then
    current_line=$((current_line + 1))
  fi

  # Only check added lines (starting with +)
  if [[ ! "$line" =~ ^\+ ]]; then
    continue
  fi

  content="${line:1}"  # Strip leading +

  [[ -z "$current_file" ]] && continue

  # Pattern 1: .gitlab-ci.yml and .gitlab/**/*.yml
  if [[ "$current_file" == ".gitlab-ci.yml" || "$current_file" == .gitlab/*.yml || "$current_file" == .gitlab/**/*.yml ]]; then
    # NON_DEFAULT_JVMS set to true
    if echo "$content" | grep -qE 'NON_DEFAULT_JVMS\s*:\s*"?true"?'; then
      warn "$current_file" "$current_line" "NON_DEFAULT_JVMS set to true (debug/temporary CI flag)"
    fi
    # Hardcoded branch names in if: or only: conditions (not master, main, release/*)
    if echo "$content" | grep -qE '^\s*(if|only)\s*:' ; then
      if echo "$content" | grep -qE '(if|only)\s*:.*\$CI_COMMIT_BRANCH\s*==\s*"[^"]*"' || \
         echo "$content" | grep -qE "(if|only)\s*:.*refs/heads/[^\s]*"; then
        # Check it's not master/main/release
        if ! echo "$content" | grep -qE '(master|main|release/)'; then
          warn "$current_file" "$current_line" "Hardcoded branch name in CI condition (not master/main/release/*)"
        fi
      fi
    fi
    # Also check for hardcoded branch refs in only/if without $CI_COMMIT_BRANCH syntax
    if echo "$content" | grep -qE '^\s*-\s*[a-zA-Z0-9_/.-]+$'; then
      branch_val=$(echo "$content" | grep -oE '[a-zA-Z0-9_/.-]+$')
      if [[ -n "$branch_val" ]] && ! echo "$branch_val" | grep -qE '^(master|main|release/.*)$'; then
        # This is too broad, skip
        :
      fi
    fi
  fi

  # Pattern 2: .github/workflows/*.yaml
  if [[ "$current_file" == .github/workflows/*.yaml || "$current_file" == .github/workflows/*.yml ]]; then
    if echo "$content" | grep -qE '^\s*if\s*:'; then
      # Check for hardcoded branch names that are not master/main/release
      if echo "$content" | grep -qE "github\.ref\s*==\s*'refs/heads/[^']*'|github\.ref_name\s*==\s*'[^']*'"; then
        if ! echo "$content" | grep -qE "(master|main|release/)"; then
          warn "$current_file" "$current_line" "Hardcoded branch name in GitHub Actions if condition (not master/main/release/*)"
        fi
      fi
    fi
  fi

  # Pattern 3: **/*.groovy and **/*.java test files
  if [[ "$current_file" == *.groovy || "$current_file" == *.java ]]; then
    if echo "$content" | grep -qF -- '-Ddd.trace.debug=true'; then
      warn "$current_file" "$current_line" "-Ddd.trace.debug=true found in test configuration (debug flag)"
    fi
  fi

  # Pattern 4: **/*.gradle and **/*.gradle.kts
  if [[ "$current_file" == *.gradle || "$current_file" == *.gradle.kts ]]; then
    # Commented-out apply plugin: or id( lines
    if echo "$content" | grep -qE '^\s*//\s*(apply plugin:|id\()'; then
      warn "$current_file" "$current_line" "Commented-out plugin declaration (suggests temporary disable)"
    fi
  fi

done <<< "$DIFF"

if [[ $VIOLATIONS -gt 0 ]]; then
  echo ""
  echo "Found $VIOLATIONS debug flag violation(s)."
  if ! $DRY_RUN; then
    exit 1
  fi
fi

exit 0
