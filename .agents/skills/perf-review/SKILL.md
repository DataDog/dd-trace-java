---
name: perf-review
description: >-
  Performance-overhead review of a code diff / branch / PR for the dd-trace-java
  tracer. Flags hot-path allocation, unbounded memory, repeated work, escaping
  objects, native-boundary crossings, and JVM-specific pitfalls (escape analysis,
  JNI / virtual-thread pinning, backtracking-regex ReDoS, varargs/boxing hashing,
  String.format, ByteBuddy-Advice anti-patterns) using the tracer performance
  rubric. Use whenever the user wants a performance / overhead / hot-path review,
  asks to check a diff or PR for allocation / GC / memory / latency / startup cost,
  or mentions the "perf rubric" or the "do no harm / assume hot" tracer posture —
  even if they just say "review this for perf" without naming the rubric. Advisory and READ-ONLY: it reports ranked,
  verify-first findings; it never edits code.
user-invocable: true
context: fork
allowed-tools:
  - Bash
  - Read
  - Grep
  - Glob
---

# Performance Review

Review the current branch's changes for performance overhead in the dd-trace-java
tracer, using the tracer performance rubric bundled in `references/`. This is a
**low-friction advisory nudge**, not a gate: it reports findings and stops. It
never edits code.

## The rubric

Read these two files before looking at the diff. They set the whole posture:

- **`references/posture.md`** — do no harm, assume hot vs. precision over recall, the core
  rules (verify-don't-verdict, confidence axis, interprocedural resolution, shipped-evidence
  check, contestable-tradeoff nudge), the severity scale, and the JVM target.
- **`references/hotness.md`** — hot anchors, the hot-path map and its multiplier, what
  counts as evidence of cold, and domain adjustment (large-denominator domains, DSM,
  streaming handlers, AppSec).

Then read the check files that apply to the diff:

| File | Checks | Load |
|---|---|---|
| `references/checks/memory.md` | #3 unbounded memory, J5 cardinality-sensitive aggregator, J6 weak-ref strengthening, J13 large retained `byte[]` | always |
| `references/checks/allocation.md` | #1 per-call allocation, #7 escape defeated, J1 EA, J4 GC → tail latency; defensive copies, capturing lambdas, `Optional`/boxing | always |
| `references/checks/repeat-work.md` | #2 repeat work, J8 backtracking regex; regex compile, `String.format`, `Config.get()`, Streams | always |
| `references/checks/critical-path.md` | #4 deferrable work, #6 FFI crossing, J3 JNI + virtual-thread pinning | always |
| `references/checks/collections.md` | wrong collection type, mis-sizing, concurrency choice | always |
| `references/checks/startup.md` | premain-reachable startup cost | always |
| `references/checks/product-off.md` | J14 work for an optional product that's off | always |
| `references/checks/dispatch.md` | #5 polymorphic dispatch, J2 megamorphic (**parked**) | always |
| `references/checks/contracts.md` | #8 `@NoEscape`, #9 `@StaticLifetime`, #10 `@BackgroundOnly` | always — these fire on code that doesn't name the annotation; check every line Step 1b lists |
| `references/checks/advice.md` | ByteBuddy Advice idioms | diff touches `dd-java-agent/` or uses `@Advice` |
| `references/checks/benchmarks.md` | J12 benchmark validity, EA claims over I/O | diff touches `src/jmh/`, or PR text / commits cite benchmark results |
| `references/remedies.md` | J7, J9, J10, J11 fix routes + toolkit availability; also holds the J7 retention trap and the J9 trigger | always |

Each check has the same parts: **Flag** (the condition to trace), **Not when** (exceptions that
keep you silent), **Confidence / level**, **Fix**, and where useful **Why** and an **Example**.
Apply a check only when its Flag line holds and no Not-when line does.

## Workflow

### Step 1 — Get the code to review

**If the user points you at specific files or pasted code** ("review this class / this
method for perf"), review those directly — skip the diff and go to Step 2 with the same
hot-path mapping and checks.

**Otherwise, review the branch changes.** Find the merge-base against the DataDog
upstream `master` and diff against it:

```bash
UPSTREAM=$(git remote -v | grep -E 'DataDog/[^/]+(\.git)?\s' | head -1 | awk '{print $1}')
[ -z "$UPSTREAM" ] && UPSTREAM="origin"
MERGE_BASE=$(git merge-base HEAD ${UPSTREAM}/master)
echo "Reviewing changes since $MERGE_BASE"
git diff $MERGE_BASE --stat
git diff $MERGE_BASE --name-status
```

Also read the claims the change makes. A performance claim can live only in the PR title
or description, which the diff doesn't show:

```bash
gh pr view --json title,body 2>/dev/null   # the branch's PR, if it has one
git log --format='%s%n%b' $MERGE_BASE..HEAD   # commit messages
```

If the branch has no PR, or `gh` can't reach it, check the commit messages and say in the
summary that the PR text wasn't available, so a claim made only there wasn't checked.

If there are no changes, say so and stop. Otherwise read the diff **and the full
content of the modified source files** (not just the hunks) — the interprocedural
condition (who calls this, what a helper does, where a hook's sink lands) lives
outside the diff window. Ignore the PR description if the user asks for an
independent review, except to find performance claims.

### Step 1b — Find code that uses the performance contracts

The contract checks (#8–#10) fire on code that never names the annotation, so don't rely on
spotting it while reading. Run this from the repository root. It runs under `bash` explicitly,
because it relies on word splitting, which `zsh` doesn't do. Set `DIFF_BASE` to the merge base from
Step 1 (it defaults to the merge base with `origin/master`):

```bash
DIFF_BASE=<merge base> bash <<'EOF'
REV=${REV:-HEAD}
DIFF_BASE=${DIFF_BASE:-$(git merge-base "$REV" origin/master)}  # the merge base from Step 1
A='NoEscape|StaticLifetime|ImpliedFor|Singleton|BackgroundOnly|ForegroundSafe'
PKG='datadog\.(perfcontract|trace\.api\.function)'
FILES=$( { git grep -l -E "import $PKG\.($A);|@$PKG\.($A)" "$REV" -- '*.java'
           git grep -l -E "@($A)([[:space:](]|$)" "$REV" -- 'internal-api/src/main/java/datadog/perfcontract/' 'internal-api/src/main/java/datadog/trace/api/function/'
         } | sed "s|^$REV:||" | grep -v -E '/(test|jmh)/' | sort -u )
[ -z "$FILES" ] && { echo "No contract annotations found."; exit 0; }

# 1. Each annotation and the element it marks (the next class/interface/enum/record or method declaration).
HITS=$(git grep -n -E "^[[:space:]]*@(($PKG\.)?($A))([[:space:](]|$)" "$REV" -- $FILES | sed "s|^$REV:||")
echo "== 1. Marked elements"
MARKED=$(echo "$HITS" | while IFS=: read -r f ln text; do
  ann=$(echo "$text" | grep -oE "@($A)" | head -1)
  if [ "$ann" = "@ImpliedFor" ]; then
    for t in $(echo "$text" | grep -oE '[A-Z][A-Za-z0-9_]*\.class' | sed 's/\.class$//'); do echo "type $t $ann $f:$ln"; done
    continue
  fi
  git show "$REV:$f" | awk -v s="$ln" -v ann="$ann" -v loc="$f:$ln" '
    NR > s && NR <= s + 20 && $0 !~ /^[[:space:]]*@/ {
      if (match($0, /(class|interface|enum|record)[[:space:]]+[A-Za-z0-9_]+/)) {
        t = substr($0, RSTART, RLENGTH); sub(/.*[[:space:]]/, "", t); print "type " t " " ann " " loc; exit }
      if (match($0, /[A-Za-z0-9_]+[[:space:]]*\(/)) {
        m = substr($0, RSTART, RLENGTH); sub(/[[:space:]]*\($/, "", m); print "method " m " " ann " " loc; exit }
    }'
done)
echo "$MARKED"
TYPES=$(echo "$MARKED" | awk '$1 == "type" { print $2 }' | sort -u)
METHODS=$(echo "$MARKED" | awk '$1 == "method" { print $2 }' | sort -u)

# 2. One hop: fields and methods declared with a marked type. Only class-level declarations
#    (with an access or static modifier) count: a local or parameter can't be used from another
#    file. Fields match by name; methods only as Declarer.name(, since short method names such as
#    of() or next() are everywhere.
TRE=$(echo $TYPES | tr ' ' '|')
DECL="($TRE)(<[^;=()]*>)?(\\[\\])?[[:space:]]+[A-Za-z_][A-Za-z0-9_]*[[:space:]]*[=;(]"
HOLDS=$(git grep -n -E "(public|protected|private|static)[^=;(]*(^|[^A-Za-z0-9_.])$DECL" "$REV" -- '*.java' ':!*/test/*' ':!*/jmh/*' \
        | sed "s|^$REV:||" | while IFS=: read -r f ln text; do
            d=$(echo "$text" | grep -oE "$DECL" | head -1)
            name=$(echo "$d" | sed -E 's/.*[[:space:]]([A-Za-z_][A-Za-z0-9_]*)[[:space:]]*[=;(]$/\1/')
            cls=$(basename "$f" .java)
            case "$d" in *'(') echo "method $cls.$name" ;; *) echo "field $name" ;; esac
          done | sort -u)
FIELDS=$(echo "$HOLDS" | awk '$1 == "field" { print $2 }')
HMETHODS=$(echo "$HOLDS" | awk '$1 == "method" { print $2 }')
echo; echo "== 2. Fields and methods declared with a marked type (one hop)"; echo $FIELDS $HMETHODS | fold -w 100 -s

# 3. Added, non-comment lines in the diff that use any of those names.
WORDS=$(printf '%s\n' $TYPES $METHODS $FIELDS | sort -u | tr '\n' '|' | sed 's/|$//')
CALLS=$(printf '%s\n' $HMETHODS | sed 's/\./\\./' | sort -u | tr '\n' '|' | sed 's/|$//')
RE="(^|[^A-Za-z0-9_])($WORDS)([^A-Za-z0-9_]|$)"
[ -n "$CALLS" ] && RE="$RE|(^|[^A-Za-z0-9_.])($CALLS)[[:space:]]*\\("
echo; echo "== 3. Added lines in the diff that use them"
git diff -U0 "$DIFF_BASE" "$REV" -- '*.java' ':!*/test/*' ':!*/jmh/*' | RE="$RE" awk '
  BEGIN { re = ENVIRON["RE"] }
  /^\+\+\+ b\// { f = substr($0, 7); next }
  /^@@/ { split($3, a, ","); n = substr(a[1], 2) + 0; next }
  /^\+/ { line = substr($0, 2)
          if (line !~ /^[[:space:]]*(\*|\/\/|\/\*)/ && match(line, re)) {
            hit = substr(line, RSTART, RLENGTH); gsub(/^[^A-Za-z0-9_]+|[^A-Za-z0-9_.]+$/, "", hit); sub(/\.+$/, "", hit)
            printf "%s:%d: [%s] %s\n", f, n, hit, line }
          n++; next }'
EOF
```

It prints three lists:

1. **Marked elements:** each contract annotation and the type or method it marks, including the
   types `@ImpliedFor` lists on `StaticLifetime`. A mark on a class covers its methods.
2. **One hop:** class-level fields and methods declared with a marked type, such as a
   `static final` cache field. Locals and parameters are left out: code in another file can't use
   them, and a declaration in the diff already names the marked type.
3. **Added lines in the diff that use either list**, tagged with the name that matched. Comment
   lines are skipped.

Check every line in list 3 against `checks/contracts.md`, however large the diff is. A hit is a
place to look, not a finding: it still has to pass the check's Flag line, and short names can match
by accident. The lookup follows one hop only, so also apply the checks to anything else you notice
while reading. Only files that import the contract annotations count, so other annotations with the
same simple name (for example `javax.inject.Singleton`) are left out.

If you can't run it (for example, you only have a diff), say in **Scope reviewed** that the contract
checks ran without it.

### Step 2 — Map the changed code onto hot paths

For each changed method, decide *which multiplier applies* before flagging anything,
using `references/hotness.md`.

### Step 3 — Apply the checks

Run the changed hot-path code against the check files in the table above. Load the
conditional ones only when their condition holds, and say in **Scope reviewed** which
conditional files you loaded.

### Step 4 — Resolve, then emit

Before writing a finding: confirm the reachability path, confirm it's unconditional
along that path (check for upstream guards), and follow any hook/callback to its sink.
Drop anything that resolves to benign. Then report in the format below.

**How many findings to report — scale with diff size:**
- **Small, focused diff** (one method, a handful of files): report *every* genuinely
  high-confidence finding, ranked by severity. A tight diff with four real allocation
  smells should list all four (as the worked example does).
- **Large PR:** lead with the 1–3 highest-severity findings and note that lower-severity
  ones may exist — don't bury the important one under a wall of CPU-micro nits.
- Either way, the gate is *confidence*, not a count: silence on the uncertain ones is
  what earns the review its credibility.

## Output format

Follow this structure (see `references/example-review.md` for a full worked instance).
Showing your suppressed lookalikes and what you cleared is not
filler: it demonstrates the precision that makes the findings trustworthy.

When providing suggestions as code review comments, prefix the comments with "perf: "
```markdown
# Perf Review — <branch / PR>

**Scope reviewed:** <the hot method(s) and why they're hot — the multiplier>

## Confirmed findings

### 1. <one-line title>
<the offending code, as a short fenced snippet>
- **Confidence:** flag-with-confidence | flag-as-measure
- **Reachability:** <hot from X via A→B→C, no guard on that path>
- **Rubric check:** <#N / JN>
- **Severity:** P<n>
- **Fix / verify-with:** <the actionable fix, or "verify with an allocation profiler">

## Correctly suppressed (not flagged)
<textual lookalikes deliberately left silent — e.g. the same `Objects.hash` pattern
but at class-init (cold), not per-call — and why the posture suppresses them>

## Checked, no issue
<what you examined and cleared: e.g. "no unbounded cache (#3/J5)", "no native
crossing (#6/J3)", "string-literal tag keys are JVM-interned — no per-call alloc">

## Summary
<count + severity spread; e.g. "4 confirmed hot-path findings, all P3
(allocation/CPU); 1 cold-path lookalike correctly suppressed">
```

If nothing survives the confidence bar, say so plainly — "No high-confidence hot-path
findings; here's what I checked and cleared." A clean review is a valid, valuable
result, not a failure to find something.
