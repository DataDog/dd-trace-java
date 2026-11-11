#!/usr/bin/env bash
# Offline fixtures stub Git reads; no staging, commits, network, or package installation.
set -euo pipefail
export LC_ALL=C

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fixture_dir="$(mktemp -d)"
trap 'rm -rf -- "$fixture_dir"' EXIT
mkdir -p "$fixture_dir/bin"
cat > "$fixture_dir/bin/git" <<'GIT'
#!/usr/bin/env bash
set -eu
if [ "$1" = --literal-pathspecs ]; then shift; fi
case "$1" in
  rev-parse)
    case "$4" in
      'baseline^{commit}') echo baseline-commit ;;
      'candidate^{commit}') echo candidate-commit ;;
      *) echo 'Unknown revision' >&2; exit 128 ;;
    esac
    ;;
  ls-tree)
    [ "$2" = --full-tree ] || exit 1
    shift
    path="$CONTEXT_FIXTURE/${2%-commit}/$4"
    if [ -L "$path" ]; then
      printf '120000 blob object\t%s\n' "$4"
    elif [ -f "$path" ]; then
      printf '100644 blob object\t%s\n' "$4"
    elif [ -d "$path" ]; then
      printf '040000 tree object\t%s\n' "$4"
    fi
    ;;
  show)
    revision="${2%%:*}"
    cat "$CONTEXT_FIXTURE/${revision%-commit}/${2#*:}"
    ;;
  *) echo "Unexpected Git command: $*" >&2; exit 1 ;;
esac
GIT
chmod +x "$fixture_dir/bin/git"
export PATH="$fixture_dir/bin:$PATH"
checks=0

reset_fixture() {
  checks=$((checks + 1))
  export CONTEXT_FIXTURE="$fixture_dir/case-$checks"
  mkdir -p "$CONTEXT_FIXTURE/baseline" "$CONTEXT_FIXTURE/candidate"
}

run_report() {
  local expected="$1" status=0
  bash "$script_dir/context_growth.sh" --base-ref "${2:-baseline}" --candidate-ref candidate \
    --report "$CONTEXT_FIXTURE/report.md" > "$CONTEXT_FIXTURE/stdout" || status=$?
  [[ "$status" -eq "$expected" ]] || {
    cat "$CONTEXT_FIXTURE/stdout" >&2
    echo "Expected exit $expected, got $status" >&2
    exit 1
  }
  cmp "$CONTEXT_FIXTURE/report.md" "$CONTEXT_FIXTURE/stdout"
}

contains() {
  grep -Fq -- "$1" "$CONTEXT_FIXTURE/report.md" || {
    cat "$CONTEXT_FIXTURE/report.md" >&2
    echo "Missing expected report text: $1" >&2
    exit 1
  }
}

reset_fixture
mkdir -p "$CONTEXT_FIXTURE/candidate/.claude"
printf 'Rules\n' > "$CONTEXT_FIXTURE/candidate/AGENTS.md"
printf '@../AGENTS.md\n' > "$CONTEXT_FIXTURE/candidate/.claude/CLAUDE.md"
run_report 0
contains '| **Total** | 0 | 20 | **+20** |'

reset_fixture
mkdir -p "$CONTEXT_FIXTURE/candidate/docs"
printf '@docs/rules.md\n@docs/rules.md\n' > "$CONTEXT_FIXTURE/candidate/AGENTS.md"
printf '@../AGENTS.md\n@detail.md\n' > "$CONTEXT_FIXTURE/candidate/docs/rules.md"
printf 'Details\n' > "$CONTEXT_FIXTURE/candidate/docs/detail.md"
run_report 0
contains '| **Total** | 0 | 63 | **+63** |'

reset_fixture
cat > "$CONTEXT_FIXTURE/candidate/AGENTS.md" <<'MARKDOWN'
Use `@TableTest` and ``@Other``.
```java
@Override
```
~~~
@Example
~~~
MARKDOWN
run_report 0

reset_fixture
mkdir -p "$CONTEXT_FIXTURE/candidate/docs"
printf 'Follow @docs/rules.md and @"docs/other rules.md"\n' > "$CONTEXT_FIXTURE/candidate/AGENTS.md"
printf 'Rules' > "$CONTEXT_FIXTURE/candidate/docs/rules.md"
printf 'More rules' > "$CONTEXT_FIXTURE/candidate/docs/other rules.md"
run_report 0
contains '| `docs/other rules.md` | 0 | 10 | +10 |'

reset_fixture
printf '@missing.md\n' > "$CONTEXT_FIXTURE/candidate/AGENTS.md"
run_report 2
contains 'INCOMPLETE:'
contains 'Required @import is missing: missing.md'

for target in '../outside.md' '/outside.md' '~/outside.md' 'C:/outside.md' '.git/config'; do
  reset_fixture
  printf '@%s\n' "$target" > "$CONTEXT_FIXTURE/candidate/AGENTS.md"
  run_report 2
  contains 'INCOMPLETE:'
done

reset_fixture
printf '@old.md' > "$CONTEXT_FIXTURE/baseline/AGENTS.md"
printf 'Old guidance' > "$CONTEXT_FIXTURE/baseline/old.md"
printf '@new.md' > "$CONTEXT_FIXTURE/candidate/AGENTS.md"
printf 'Caf\303\251' > "$CONTEXT_FIXTURE/candidate/new.md"
run_report 0
contains '| `new.md` | 0 | 5 | +5 |'
contains '| `old.md` | 12 | 0 | -12 |'
contains '| **Total** | 19 | 12 | **-7** |'

reset_fixture
run_report 0
contains '| **Total** | 0 | 0 | **+0** |'

reset_fixture
printf '@alias.md' > "$CONTEXT_FIXTURE/candidate/AGENTS.md"
printf 'Rules' > "$CONTEXT_FIXTURE/candidate/rules.md"
ln -s rules.md "$CONTEXT_FIXTURE/candidate/alias.md"
run_report 2
contains 'regular file (no symlinks): alias.md'

reset_fixture
run_report 2 missing
contains 'Unknown revision'

printf 'PASS: %d context-growth fixtures.\n' "$checks"
