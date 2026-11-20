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
| `references/checks/benchmarks.md` | J12 benchmark validity, EA claims over I/O, J15 required results, J16 recorded findings | diff touches `src/jmh/`, PR text / commits cite benchmark results, or Step 1c lists a benchmark |
| `references/remedies.md` | J7, J9, J10, J11 fix routes + toolkit availability; also holds the J7 retention trap and the J9 trigger | always |

Each check has the same parts: **Flag** (the condition to trace), **Not when** (exceptions that
keep you silent), **Confidence / level**, **Fix**, and where useful **Why** and an **Example**.
Apply a check only when its Flag line holds and no Not-when line does.

## Workflow

### Step 1 — Get the code to review

**If the user points you at specific files or pasted code** ("review this class / this
method for perf"), review those directly: skip the diff, run Step 1b with `--paths` set to the
files, then go on to Step 2 with the same hot-path mapping and checks. Pasted code can't go
through Step 1b, so say so in **Scope reviewed**.

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
spotting it while reading. Run the lookup, passing the merge base from Step 1:

```bash
python3 tooling/contract_lookup.py --diff-base <merge base>
```

It reads the working tree, including uncommitted edits, so it sees the same code as Step 1. Pass
`--rev <commit>` to scan a commit instead, or `--paths <file> ...` for a direct review of files.

It prints three lists:

1. **Marked elements:** each contract annotation and the type, field or method it marks, plus the
   types `@ImpliedFor` lists on `StaticLifetime` and their subtypes. A mark on a class covers its
   methods.
2. **One hop:** class-level fields and methods declared with a marked type, such as a
   `static final` cache field.
3. **Lines that use either list**, tagged with the name that matched: the diff's added lines, or
   every line of `--paths`. In those files, a call through a local or parameter of a marked type
   (`cache.getUtf8(...)`) counts too. Comment lines are skipped.

Check every line in list 3 against `checks/contracts.md`, however large the diff is. A hit is a
place to look, not a finding: it still has to pass the check's Flag line, and short names can match
by accident. The lookup follows one hop only, so also apply the checks to anything else you notice
while reading. Only files that import the contract annotations count, so other annotations with the
same simple name (for example `javax.inject.Singleton`) are left out.

If you can't run it (for example, you only have a diff), say in **Scope reviewed** that the contract
checks ran without it.

### Step 1c — Find the benchmarks that cover the diff

`.github/required-benchmarks.txt` maps hot code to the JMH benchmarks that measure it. List the ones
this diff requires:

```bash
python3 tooling/benchmark_pr.py required --base <merge base>
```

For each benchmark it lists, read the **Findings** section at the top of the benchmark class's
Javadoc, if it has one (see `checks/benchmarks.md`): recorded conclusions about the code under
review. Then apply the J15 and J16 checks in `checks/benchmarks.md`. Load that file whenever this
step lists a benchmark.

### Step 2 — Map the changed code onto hot paths

For each changed method, decide *which multiplier applies* before flagging anything,
using `references/hotness.md`.

**Unchanged code is in scope if the diff changes what it costs** — it now runs more often,
under a new lock, or on the application thread. Flag it as *carried in by this change*
(see `references/hotness.md`). Propagation and header paths are on the per-span core, so the
core raise in `references/posture.md` § Severity applies to them.

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
