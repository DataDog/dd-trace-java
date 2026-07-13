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
  mentions the "perf rubric" or the "do no harm / assume hot" tracer posture, or is
  about to open a PR touching span lifecycle, tag maps, serialization/encoding,
  decorators, propagation, or instrumentation — even if they just say "review this
  for perf" without naming the rubric. Advisory and READ-ONLY: it reports ranked,
  verify-first findings; it never blocks a merge and never edits code.
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
never edits code and never blocks a merge.

## Why this exists (read first — it sets the whole posture)

The tracer shares the customer's process, heap, and latency budget. **Do no harm**:
overhead is a form of incorrect behavior that can escalate to real customer harm —
missed SLAs, OOM kills, container restarts, cold-start churn. So the review's job is
to catch overhead the customer would feel, and to do it *without becoming noise*.

Two forces are in tension, and the resolution defines everything below:

- **Assume hot.** We don't know what's on a customer's critical path. Absent positive
  evidence of cold, assume the code runs on every request, under load, at full
  concurrency. The burden of proof runs toward *cold*: ask "is there evidence this is
  cold or guarded?" — not "is there evidence this is hot?" (that rationalizes itself
  into "probably not").
- **Precision over recall — be silent when unsure.** A false-positive-prone review
  dies of being ignored. Over-flagging kills it faster than under-flagging. This
  actively fights your default to be comprehensive and helpful: here, *not* flagging
  a borderline case is the correct, skilled move — not a miss.

You reconcile them with the **confidence axis** and **verify-don't-verdict** (below):
assume-hot makes you *look* everywhere; precision makes you *speak* only when the
mechanism is certain or the severity is catastrophic.

## Core rules

- **Findings are prompts to *verify*, not verdicts.** You reason statically; you
  cannot render a performance verdict from a code read. Every finding routes into
  **Benchmark → Profile → Improve → Guard**. Phrase each as *"this looks like X;
  verify with Y"* — never "this is slow."
- **Confidence axis on every finding:**
  - **flag-with-confidence** — the cost is *mechanism-determined* and visible in the
    code: allocation, boxing, copying, unbounded growth, a native crossing. State it
    plainly.
  - **flag-as-measure** — the cost depends on JIT/GC/optimizer decisions you can't
    see from source: escape elision, inlining/devirtualization, GC impact. Phrase as
    "may X; verify with a profiler/benchmark," never as a certainty.
- **Predicate-with-default, not a banned-API list.** Don't flag "you called
  `String.format`." Flag *"an eager, unconditional expensive call on a hot,
  instrumentation-reachable path."* The same API is fine on a cold path. Two failure
  shapes, different fixes: result usually **discarded** → gate/defer; result always
  **needed but costly** → cheapen/cache.
- **Resolve interprocedurally — this is the review's whole reason to exist.** A
  peephole lint can't answer "reachable from a hot entry, unconditional along the
  way." Trace *up* (who calls this? is it reachable from an `@Advice` root / per-span
  callback / request handler?) and *down* (follow callbacks, hooks, and listeners to
  their **sink** before flagging). If a per-span hook's every reachable sink is an
  atomic counter (`LongAdder`, `AtomicLong`) or a no-op-when-disabled, stay silent — a
  "verify contention" nudge there is noise.
- **Make the reachability path the headline.** The reachability claim is the most
  valuable *and* least reliable part of a finding — residual false positives cluster
  in "called it unconditional, missed an upstream guard." Say *"reachable from
  `Foo.onEnter` via A→B→C, no guard on that path"* so the reader can check the
  shakiest link at a glance.
- **Only flag toward a fix that exists.** A finding must be actionable *now*. Route to
  a mechanism that has landed (see the toolkit note in `checks.md` — cite only what
  exists; name "coming" primitives as coming). Don't flag a pattern whose only fix is
  a mechanism that isn't built yet.
- **Triage by severity.** Flag SEV-1 (unbounded memory / OOM, cardinality blowups)
  *aggressively* — a false positive there is cheap insurance against a container kill.
  Flag low-severity CPU-micro *conservatively or not at all* — false positives there
  only erode trust.
- **Never flag the *absence* of a cache on high-cardinality input.** For open-cardinality
  data (raw SQL with literals, per-request strings), *not* caching is the correct
  choice — caching it would be the worse SEV-1. Flag a cache *keyed by* high-cardinality
  data; never flag the decision not to cache.
- **A *visibly contestable* perf tradeoff shipped without data → one soft flag-as-measure.**
  The trigger is narrow: the change makes a **visible tradeoff that could itself regress** —
  it removes a lock / guard / synchronization, swaps in a hand-rolled cache or data
  structure, or explicitly claims "faster / optimized" — **and** ships no benchmark or
  profile. There a static read genuinely can't tell a win from a regression, so raise one
  soft *flag-as-measure* nudge: *"this trades <X for Y>; verify with a JMH benchmark / JFR."*
  Do **not** fire it otherwise — if nothing in the diff could plausibly regress, there is
  nothing to measure, so stay silent. Specifically not for: a **mechanically-obvious win**
  (hoisting an invariant out of a loop, a denser data structure, removing an allocation);
  **routine adoption of a known-better idiom** (migrating to a lower-overhead builder / API /
  toolkit primitive — no visible downside); or a change that **ships a benchmark/JFR**
  (well-evidenced — recognize it). One line; a nudge, not a code-pattern finding.

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

If there are no changes, say so and stop. Otherwise read the diff **and the full
content of the modified source files** (not just the hunks) — the interprocedural
condition (who calls this, what a helper does, where a hook's sink lands) lives
outside the diff window. Ignore the PR description if the user asks for an
independent review.

### Step 2 — Map the changed code onto hot paths

For each changed method, decide *which multiplier applies* before flagging anything.

**Hot anchors** (reachable ⇒ assume hot): `@Advice.OnMethodEnter`/`OnMethodExit`,
per-span / per-trace callbacks, request / message handlers, streaming chunk handlers.
**Hot-path map** (where cost is multiplied per-span × spans/request × requests/sec):
span lifecycle (create / setTag / finish), tag-map ops, serialization/encoding, the
metrics/stats path, decorators, propagation (header read/write).

**Cold only with positive evidence:** one-time init, startup-only path, a genuinely
rare error branch, or behind a guard that provably fires rarely. Watch the
**interprocedural trap** — a method three helpers deep from an `@Advice` entry is
still hot. And note **domain adjustment**: large-denominator domains (LLMObs, CI
Visibility, DSM) absorb per-call CPU/alloc cost, but the risk *inverts* to payload
memory (SEV-1); streaming handlers fire per-chunk, so the large-denominator relief
suspends inside them. See `guide.md` §6.

### Step 3 — Apply the checks

Run the changed hot-path code against the rubric. Keep the check index below in mind;
open the references for the precise conditions, confidence, severity, and fix:

- **`references/guide.md`** — the narrative "how": severity model, hotness rubric,
  the 6 categories with worked examples, and the false-positive traps. Read this first
  if you're calibrating judgment.
- **`references/checks.md`** — the precise cost-model: 7 universal checks + the Java
  addendum (J1–J11) + the ByteBuddy-Advice fix idioms + the toolkit-availability note.
  Read this for the exact confidence/severity/fix of a specific pattern.

### Step 4 — Resolve, then emit

Before writing a finding: confirm the reachability path, confirm it's unconditional
along that path (check for upstream guards), and follow any hook/callback to its sink.
Drop anything that resolves to benign. Then report in the format below.

**How many findings to report — scale with diff size:**
- **Small, focused diff** (one method, a handful of files): report *every* genuinely
  high-confidence finding, ranked by severity. A tight diff with four real allocation
  smells should list all four (as the worked example does).
- **Large PR:** lead with the 1–2 highest-severity findings and note that lower-severity
  ones may exist — don't bury the important one under a wall of CPU-micro nits.
- Either way, the gate is *confidence*, not a count: silence on the uncertain ones is
  what earns the review its credibility.

## Output format

Follow this structure (see `references/example-review.md` for a full worked instance —
PR #11903, Bucket4j). Showing your suppressed lookalikes and what you cleared is not
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
- **Severity:** SEV-<n>
- **Fix / verify-with:** <the actionable fix, or "verify with an allocation profiler">

## Correctly suppressed (not flagged)
<textual lookalikes deliberately left silent — e.g. the same `Objects.hash` pattern
but at class-init (cold), not per-call — and why the posture suppresses them>

## Checked, no issue
<what you examined and cleared: e.g. "no unbounded cache (#3/J5)", "no native
crossing (#6/J3)", "string-literal tag keys are JVM-interned — no per-call alloc">

## Summary
<count + severity spread; e.g. "4 confirmed hot-path findings, all SEV-2/3
(allocation/CPU); 1 cold-path lookalike correctly suppressed">
```

If nothing survives the confidence bar, say so plainly — "No high-confidence hot-path
findings; here's what I checked and cleared." A clean review is a valid, valuable
result, not a failure to find something.

## Check index (the map — details in the references)

**Universal (language-agnostic):**
1. Per-span/per-call allocation on a hot path (retained/escaping) — SEV-2/3
2. Repeat work across calls (regex compile / format / parse / concat recomputed) — SEV-2/3
3. Unbounded memory / collection, or keyed by high-cardinality input — **SEV-1**
4. Expensive work on the critical path that could be deferred — SEV-1/2
5. Polymorphic dispatch defeating inlining/devirt — flag-as-measure — SEV-2/3
6. FFI / native-boundary crossing per-item (not batched) — SEV-1/2
7. Escape / allocation-elision defeated by a refactor — flag-as-measure — SEV-2/3

**Java addendum (JVM-specific — full text + mechanism in `checks.md`):**
- **J1** escaping allocation defeats Escape Analysis · **J3** JNI crossing + virtual-thread
  pinning · **J4** GC pressure → tail latency · **J5** cardinality-sensitive aggregator
  (**SEV-1**) · **J6** `WeakReference.get()` in a probe loop strengthens the ref ·
  **J7** `substring` → `SubSequence` zero-copy view · **J8** backtracking regex on
  external input → RE2J (ReDoS) · **J9** `Objects.hash(...)` varargs/boxing →
  `HashingUtils` · **J10** hot-path `String.format` → `Strings` · **J11** composite-key
  maps → `Hashtable`.
- **J2** megamorphic dispatch is **PARKED** — do *not* raise megamorphism findings in
  review yet (kept as author reference only; it needs a standing audit, not per-PR
  flagging). See `checks.md` for why.
- ByteBuddy-Advice idioms (`Config.get()` hoisting, `@Advice.AllArguments` →
  `@Advice.Argument`, `@Advice.SkipOn`+cached-boolean, `@Advice.Local`, `switch(String)`
  three-tier) — in `checks.md`.

J7–J11 route an *existing* #1/#2/#3 finding to a landed reusable fix — they are not new
triggers. Don't raise a finding you wouldn't have raised anyway.
