#!/usr/bin/env bash
set -euo pipefail

CONVENTIONAL_COMMIT_PREFIX_RE='^(fix|feat|chore|docs|refactor|test|build|ci|perf|style)(\([^)]+\))?:[[:space:]]*'
TAG_PREFIX_RE='^(\[[A-Z][A-Z0-9_/-]*\][[:space:]]*|[A-Z][A-Z0-9_]*[[:space:]]*-[[:space:]]*)+'
MAX_LEN=100

check_dependencies() {
    local missing=()
    for cmd in gh jq claude; do
        if ! command -v "$cmd" &> /dev/null; then
            missing+=("$cmd")
        fi
    done
    if [ ${#missing[@]} -gt 0 ]; then
        echo "❌ Missing required dependencies: ${missing[*]}" >&2
        echo "Please install them and try again." >&2
        exit 1
    fi
    if ! gh auth status &> /dev/null; then
        echo "❌ gh is not authenticated. Run 'gh auth login'." >&2
        exit 1
    fi
}

confirmOrAbort() {
    read -p "❔ $1 (y/N): " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo "Aborting."
        exit 0
    fi
}

strip_prefixes() {
    local t="$1"
    t=$(sed -E "s/$CONVENTIONAL_COMMIT_PREFIX_RE//" <<<"$t")
    t=$(sed -E "s/$TAG_PREFIX_RE//" <<<"$t")
    echo "$t"
}

deterministic_suggest() {
    local t
    t=$(strip_prefixes "$1")
    if [ -z "$t" ]; then
        echo "$1"
        return
    fi
    local first="${t:0:1}"
    local rest="${t:1}"
    echo "$(tr '[:lower:]' '[:upper:]' <<<"$first")$rest"
}

validate_title() {
    local t="$1"
    local reasons=()
    if [[ "$t" =~ $CONVENTIONAL_COMMIT_PREFIX_RE ]]; then
        reasons+=("conventional-commit prefix")
    fi
    if [[ "$t" =~ $TAG_PREFIX_RE ]]; then
        reasons+=("bracket/tag prefix")
    fi
    local stripped
    stripped=$(strip_prefixes "$t")
    if [[ "$stripped" =~ ^[a-z] ]]; then
        reasons+=("does not start with a capital")
    fi
    if [ "${#t}" -gt "$MAX_LEN" ]; then
        reasons+=("too long (${#t} > $MAX_LEN)")
    fi
    if [ ${#reasons[@]} -gt 0 ]; then
        local IFS=", "
        echo "${reasons[*]}"
    fi
}

claude_suggest() {
    local original="$1"
    local prompt
    prompt="Rewrite this dd-trace-java PR title to follow our conventions.
Rules: start with a capital infinitive verb (Add, Fix, Update, Refactor, Improve); no conventional-commit prefix (no \"fix:\", \"feat:\", \"chore(ci):\", etc.); no bracket/tag prefix (no \"[CORE]\", \"PROD -\"); keep under ${MAX_LEN} chars; preserve technical specificity.
Examples of good titles:
  Add support for virtual thread pinning events in JFR profiler
  Fix NPE in Kafka consumer instrumentation under retry
  Refactor HttpServerDecorator to share logic with gRPC
Original: ${original}
Output ONLY the rewritten title on a single line, no quotes, no explanation."
    claude -p "$prompt" 2>/dev/null | head -1 | sed -E 's/^[[:space:]"'\'']+//; s/[[:space:]"'\'']+$//'
}

select_milestone() {
    local raw
    raw=$(gh api "repos/{owner}/{repo}/milestones" \
        --jq '.[] | "\(.number)\t\(.title)\t\(.open_issues)\t\(.closed_issues)"')
    if [ -z "$raw" ]; then
        echo "ℹ️  No open milestones found." >&2
        exit 0
    fi
    local -a milestones
    mapfile -t milestones <<<"$raw"
    local ms_title
    if [ "${#milestones[@]}" -eq 1 ]; then
        IFS=$'\t' read -r _ ms_title _ _ <<<"${milestones[0]}"
        echo "$ms_title"
        return
    fi
    echo "Open milestones:" >&2
    local i=1
    for m in "${milestones[@]}"; do
        IFS=$'\t' read -r num title open closed <<<"$m"
        printf "  %d) %s  (#%s, %s open, %s closed)\n" "$i" "$title" "$num" "$open" "$closed" >&2
        i=$((i+1))
    done
    local choice
    read -r -p "Select milestone [1-${#milestones[@]}]: " choice
    if ! [[ "$choice" =~ ^[0-9]+$ ]] || [ "$choice" -lt 1 ] || [ "$choice" -gt "${#milestones[@]}" ]; then
        echo "❌ Invalid selection." >&2
        exit 1
    fi
    IFS=$'\t' read -r _ ms_title _ _ <<<"${milestones[$((choice-1))]}"
    echo "$ms_title"
}

main() {
    check_dependencies

    local milestone_title
    milestone_title=$(select_milestone)

    if [[ "$milestone_title" == *\"* ]]; then
        echo "❌ Milestone title contains a double quote, which would break the gh search syntax." >&2
        exit 1
    fi

    confirmOrAbort "Review PR titles in milestone '$milestone_title'?"

    echo "ℹ️ Fetching PRs from milestone '$milestone_title'..."
    local prs_json
    prs_json=$(gh pr list --search "milestone:\"$milestone_title\"" \
        --state all --limit 500 \
        --json number,title,state,url,labels)

    local prs
    prs=$(jq -c '.[] | select(.state == "MERGED" or .state == "OPEN")' <<<"$prs_json")

    if [ -z "$prs" ]; then
        echo "ℹ️ No merged or open PRs in milestone '$milestone_title'."
        exit 0
    fi

    local total=0 ok=0 fixed=0 skipped=0 no_release_note=0
    while IFS= read -r pr <&3; do
        total=$((total+1))
        local num title url state reasons
        num=$(jq -r '.number' <<<"$pr")
        title=$(jq -r '.title' <<<"$pr")
        url=$(jq -r '.url' <<<"$pr")
        state=$(jq -r '.state' <<<"$pr")
        if jq -e '.labels | any(.name == "tag: no release notes")' <<<"$pr" >/dev/null; then
            no_release_note=$((no_release_note+1))
            continue
        fi
        reasons=$(validate_title "$title")
        if [ -z "$reasons" ]; then
            ok=$((ok+1))
            continue
        fi

        local deterministic_suggestion ai_suggestion labels
        deterministic_suggestion=$(deterministic_suggest "$title")
        labels=$(jq -r '[.labels[].name] | join(", ")' <<<"$pr")
        echo
        echo "── PR #$num ($state) — $url"
        echo "  ❌ $title"
        if [ -n "$labels" ]; then
            echo "     labels: $labels"
        fi
        printf "     issues: \033[1;31m%s\033[0m\n" "$reasons"

        printf "  asking Claude..."
        ai_suggestion=$(claude_suggest "$title" || true)
        printf "\r\033[K"
        echo "  1) deterministic: $deterministic_suggestion"
        if [ -n "$ai_suggestion" ]; then
            echo "  2) claude:        $ai_suggestion"
        else
            echo "  2) claude:        (no suggestion)"
        fi
        echo "  3) custom"
        echo "  s) skip"

        local choice new
        read -r -p "  Choose [1/2/3/s]: " choice
        case "$choice" in
            1) new="$deterministic_suggestion" ;;
            2)
                if [ -z "$ai_suggestion" ]; then
                    echo "  ⚠️  No Claude suggestion available, skipping."
                    skipped=$((skipped+1))
                    continue
                fi
                new="$ai_suggestion"
                ;;
            3)
                read -r -p "  New title: " new
                if [ -z "$new" ]; then
                    echo "  ⚠️  Empty title, skipping."
                    skipped=$((skipped+1))
                    continue
                fi
                ;;
            *)
                echo "  ⏭ skipping"
                skipped=$((skipped+1))
                continue
                ;;
        esac

        if gh pr edit "$num" --title "$new" >/dev/null; then
            echo "  ✅ updated to: $new"
            fixed=$((fixed+1))
        else
            echo "  ❌ gh pr edit failed for PR #$num"
            skipped=$((skipped+1))
        fi
    done 3<<<"$prs"

    echo
    echo "── Summary ──"
    echo "  total:    $total"
    echo "  ✅ ok:    $ok"
    echo "  ✏️ fixed: $fixed"
    echo "  ⏭️ skipped: $skipped"
    echo "  🏷️ no release note: $no_release_note"
}

main "$@"
