# Posture

Read this first. It sets how every check in `checks/` is applied.

## Tracer principles

**Do no harm.** The tracer shares the customer's process, heap, and latency budget. Harm is
ordered by severity: crashes first, then security, then incorrect behavior, then adverse
performance. Performance overhead is a form of incorrect behavior — a non-directly-observable
side effect that can rise to directly observable customer harm: missed SLAs, OOM kills, container
restarts, cold-start churn. So the review's job is to catch overhead the customer would feel, and
to do it *without becoming noise*.

Two forces are in tension, and the resolution defines everything below:

- **Assume hot.** We don't know what's on a customer's critical path. Absent positive
  evidence of cold, assume the code runs on every request, under load, at full
  concurrency. The burden of proof runs toward *cold*: ask "is there evidence this is
  cold or guarded?" — not "is there evidence this is hot?" (that rationalizes itself
  into "probably not"). See `hotness.md`.
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
- **Check for shipped evidence before raising *any* flag-as-measure finding —
  not just the contestable-tradeoff nudge.** Before asking the reader to verify a
  JIT/GC-dependent cost (J1 escape elision, J4/J7 GC pressure, J5/J11 aggregator
  cardinality, etc.), check whether the diff itself already ships the answer: a JMH
  benchmark or profiler run whose stated purpose is exactly this mechanism (e.g. a
  benchmark class's Javadoc saying "run with `-Pjmh.profilers=gc` to confirm X is
  scalar-replaced"), or evidence already recorded in the PR/commit history that the
  same question was measured. If that evidence directly covers the mechanism, don't
  re-flag it as open — move it to **Correctly suppressed** / **Checked, no issue**
  and cite the benchmark/evidence by name. Only raise a finding over it if the
  evidence doesn't actually cover the claimed mechanism (wrong JVM, wrong code path,
  never actually run) or is stale relative to the current diff.
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
  "verify contention" nudge there is noise. That clears the *call* only: arguments built
  eagerly for a disabled product's no-op are still paid for (J14).
- **Make the reachability path the headline.** The reachability claim is the most
  valuable *and* least reliable part of a finding — residual false positives cluster
  in "called it unconditional, missed an upstream guard." Say *"reachable from
  `Foo.onEnter` via A→B→C, no guard on that path"* so the reader can check the
  shakiest link at a glance.
- **Only flag toward a fix that exists.** A finding must be actionable *now*. Route to
  a mechanism that has landed (see `remedies.md` — cite only what exists; name "coming"
  primitives as coming). Don't flag a pattern whose only fix is a mechanism that isn't
  built yet.
- **Triage by severity.** Flag P1 (unbounded memory / OOM, cardinality blowups)
  *aggressively* — a false positive there is cheap insurance against a container kill.
  Flag low-severity CPU-micro *conservatively or not at all* — false positives there
  only erode trust.
- **Never flag the *absence* of a cache on high-cardinality input.** Not caching is correct
  there; see "Not when" on #3 in `checks/memory.md`.
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
- **Shipped core code is stricter — see the core performance rules in `AGENTS.md`.**
  For changes to `dd-trace-core/src/main/` or `internal-api/src/main/`: a performance claim
  (in the title, description, or commits) without JMH before/after numbers is a finding, not
  a soft nudge. Elsewhere, including those modules' tests, benchmarks and build files, keep
  the softer posture above.

## Severity

The scale is the one `/sdk-review` uses, so findings from both skills rank the same way:

- `P0`: catastrophic or actively exploitable; stop publication.
- `P1`: likely severe user impact, data exposure, or a broken release.
- `P2`: meaningful defect, compatibility risk, or maintenance cost.
- `P3`: localized low-impact issue with a clear fix.

Performance findings top out at `P1`; `P0` is not used for them. How the usual causes map:

| Level | Type | Usual cause |
|---|---|---|
| **P1** | OOM / container kill | Unbounded memory growth, cardinality blowups |
| **P2** | Response time — median | Expensive work on the critical path (P1 if it blocks, pins a carrier thread, or is unbounded) |
| **P2** | Response time — tail latency | Allocation rate → GC pauses (shared heap) |
| **P2** | Startup latency | Eager class loading, init, transformation |
| **P3** | CPU overhead | General tracer activity, background work |

Each check gives one base level and says when it moves. One rule applies to every check: **raise
one level when the cost lands on the tracer's own per-span core** (span create / setTag / finish,
tag-map ops, propagation, serialization), which every integration pays.

CPU overhead alone is the lowest priority — it's a cost issue, not a correctness one, and
escalates only when it causes latency.

**The denominator matters.** Severity is cost relative to the instrumented operation. A 2 µs tag
op on a sub-millisecond HTTP span is a large fraction of the operation. The same 2 µs on a 500 ms
LLM call is negligible. See `hotness.md` (Domain adjustment) for which domains get relief and
where the risk inverts.

**Default-state changes multiply severity.** A one-line `DEFAULT_X_ENABLED = true` flip applies
the enabled-path cost to every user. Scrutinize heavily regardless of diff size.

## JVM target

The JVM-specific checks (the `J` IDs) state mechanisms with JIT-developer authority;
**calibrate production-priority against your own escalation history**.

**Scope (2026-07-08):** the primary optimization target is **C2 / Java 11+ (HotSpot)** — the
mechanisms are stated in those terms (inline-cache/`TypeProfileWidth` model, C2 speculative
inlining, EA). C1-only, OpenJ9/J9, GraalVM, and Java 8 should still benefit but are the minority
case we don't *tune* for. Pairs with the benchmark JVM standardization (Java 17 HotSpot).
