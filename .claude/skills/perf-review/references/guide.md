# Java Tracer Performance Review Guide

---

## Tracer Principles

Two lines establish everything that follows.

**Do no harm.** The tracer shares the customer's process, heap, and latency budget. Harm is
ordered by severity: crashes first, then security, then incorrect behavior, then adverse
performance. Performance overhead is a form of incorrect behavior — a non-directly-observable
side effect that can rise to directly observable customer harm: missed SLAs, OOM kills, container
restarts, cold-start churn.

**Assume hot.** We don't know a priori what will be on the critical path in a customer's
application. In the absence of evidence, assume the code runs on every request, under load, at
full concurrency. There are exceptions — schedulers, startup code, I/O-heavy paths — but the
default is: *assume hot unless there is positive evidence of cold*.

**Advisory, not blocking.** The rubric is a low-friction nudge alongside the developer's path —
not a wall across it. Flag the 1–2 highest-severity findings per PR. Stay silent when unsure.
Over-flagging kills the check faster than under-flagging.

---

## Severity Guidelines

| Severity | Type | Usual Cause |
|---|---|---|
| **SEV-1** | OOM / container kill | Unbounded memory growth |
| **SEV-1/2** | Response time — median | Expensive work on the critical path |
| **SEV-1/2** | Response time — tail latency | Allocation rate → GC pauses (shared heap) |
| **SEV-2** | Startup latency | Eager class loading, init, transformation |
| **SEV-2/3** | CPU overhead | General tracer activity, background work |

CPU overhead alone is the lowest priority — it's a cost issue, not a correctness one, and
escalates only when it causes latency.

**The denominator matters.** Severity is cost relative to the instrumented operation. A 2 µs tag
op on a sub-millisecond HTTP span is a large fraction of the operation. The same 2 µs on a 500 ms
LLM call is negligible. Large-denominator domains (LLMObs, CI Visibility, DSM) get lower
CPU/alloc severity — but the risk *inverts*: payload memory (large prompts, job metadata,
accumulated output) becomes SEV-1.

**Default-state changes multiply severity.** A one-line `DEFAULT_X_ENABLED = true` flip applies
the enabled-path cost to every user. Scrutinize heavily regardless of diff size.

---

## Hotness Rubric

The key question when reviewing any code: *is this on a hot path?*

The default answer is yes. The burden of proof runs toward cold. Ask "is there evidence this is
cold or guarded?" — not "is there evidence this is hot?" (that rationalizes itself into "probably
not").

**Hot anchors.** Paths are hot when reachable from:
- `@Advice.OnMethodEnter` / `@Advice.OnMethodExit` (per-advised-call)
- Per-span or per-trace callbacks
- Request or message handlers
- Streaming chunk handlers (even if the overall stream is slow — see §6)

**Cold only with positive evidence.** A path is cold if it is: a one-time init, a startup-only
path, a genuinely rare error branch, or behind a guard that provably fires rarely.

**Watch for the interprocedural trap.** Hot entry points are often indirect. A method buried
three helpers deep from an `@Advice` entry is still hot. Trace up before assuming cold.

---

## Categories of Issues

In approximate order of severity and frequency:

1. **Unbounded Memory** — collections or aggregators that grow without a bound
2. **Repeated Allocation on Hot Paths** — regex compile, format strings, streams per call
3. **Per-span Escaping Allocation** — wrapper objects, defensive copies, capturing lambdas
4. **Wrong Collection Type** — heavier type than needed, missing pre-sizing
5. **Startup Latency** — eager work on the premain critical path
6. **Domain-Adjusted Severity** — large-denominator domains, streaming handlers

---

## 1. Unbounded Memory (SEV-1 — flag aggressively)

A collection that grows without a bound can kill the customer's container. The tracer shares the
application heap — there is no isolation. A false positive here is cheap insurance against a
container kill. Flag aggressively.

**Raw unbounded cache.** No size or byte bound, keyed by data that grows with load (URL, SQL,
resource names). Fix: `DDCache.newFixedSizeWeightedCache(n, weigher, maxBytes)`.

**Cardinality-sensitive aggregator.** A collection with a nominal size bound, but keyed by data
that explodes in cardinality (tag combinations, user-supplied dimensions). High-cardinality input
thrashes the eviction policy — the nominal cap doesn't help. Flag when config or user-driven
values feed such an aggregator without a key-space constraint.

**Open-cardinality keys.** Any field that varies per-message — timestamp, offset, correlation ID
— used as a key component makes the aggregator grow without bound. Fix: remove the
open-cardinality dimension, or replace raw timestamps with time-buckets.

**Externally-driven caps.** Any collection grown by Remote Config, user input, or an external
control plane has its growth controlled by the external source. When a PR removes an existing cap
with no replacement bound, flag and ask — the decision may be intentional but must be explicit.

> **False-positive trap.** Flagging the *absence* of a cache on open-cardinality input (raw SQL
> with inline literals, per-request strings) is wrong. Not caching high-cardinality data *is* the
> correct choice — caching it would be the worse SEV-1. Flag a cache *keyed by* high-cardinality
> data; never flag the decision not to cache.

*Examples (patterns from back-test calibration):*
- A `LoadingCache` keyed by URL and SQL text with no size or byte limit — the capstone
  pattern: unbounded growth tied directly to traffic volume.
- A DSM pathway hash that included a timestamp field, making the aggregator's slot count
  grow without bound. Removing the timestamp from the key is a SEV-1 prevention.

---

## 2. Repeated Allocation on Hot Paths (SEV-2/3)

Tracing is repetitive. Work repeated per-span or per-trace compounds quickly. The focus is on
*allocating* repeat work — patterns that produce garbage the GC must collect — not pure CPU-micro
work like an `.equals()` call.

**Regex compile per call.** `Pattern.compile(...)` at a non-static site allocates and compiles on
every call. Fix: `static final Pattern`.

**`String.format` / format-string parsing.** Re-parses and allocates per call. Fix: direct
concatenation, or pre-compute the result. Also watch for locale-dependent formatting crossing the
wire — a correctness issue on top of the perf one.

**`Config.get()` per call.** Walks a config-resolution chain; not a free read. Fix: hoist to a
`static final` field at class initialization. This is the single most recurring DBM finding —
surfaced independently in five separate PRs.

**Java Streams on hot paths.** `stream()` and `parallelStream()` always allocate `Spliterator`
and pipeline objects. JIT elision is fragile — small changes to the pipeline or surrounding code
break it silently. Fix: plain `for` loop. Any of `cstyleFor`, `enhancedFor`, `forEach`, or
`iterator` are equivalent and zero-allocation. Benchmark evidence (Java 17, M1, 8 threads,
@Fork(2)): plain loops allocate ≈ 10⁻⁷ B/op; `stream()` always allocates 56–88 B/op;
`parallelStream()` scales from 128 B/op (empty list) to 5 200 B/op (100-element list).

**`@Advice.AllArguments()`.** Materializes a new `Object[]` boxing all method arguments on every
advised call — always escapes, EA cannot elide it. Fix: `@Advice.Argument(value=N)` for the
specific argument and type needed.

*Examples (patterns from back-test calibration):*
- A per-trace path with regex compile per call + `String.format` + a locale-dependent formatting
  bug — the clearest recall case for mechanism-certain patterns.
- A `StringBuilder(1024)` per query for a ~200-character result. Two-sided error:
  under-size causes realloc, over-size wastes memory. Target accurate, not generous.

---

## 3. Per-span Escaping Allocation (SEV-2/3, can reach SEV-1 via tail latency)

The tracer shares the application heap. Additional allocation contributes to GC and raises
stop-the-world pauses — directly increasing tail latency for the customer's application. The JVM's
escape analysis eliminates *local* short-lived allocations, but only when the object stays local.
Stored in a map, returned, captured by a lambda, or passed to a non-inlined virtual call: it
escapes, and it's real.

**EA claims for scope/wrapper objects spanning I/O — treat as unverified.** A microbenchmark
tight-loop can show zero allocation for a scope or wrapper object because C2 inlines through
everything and scalar-replaces it. In production, scopes almost always wrap I/O — and C2 cannot
inline through native/blocking I/O boundaries. The object's lifetime extends across the call, it
escapes, and it allocates. A benchmark without I/O-wrapping is not a credible check. Treat EA
claims about per-span scope objects as unverified unless the benchmark explicitly includes
realistic I/O usage.

**Defensive copies at internal boundaries.** `array.clone()`, `new ArrayList<>(other)` — justified
at real trust boundaries (public API, genuinely mutable external input); wasteful
internal-to-internal where we control all callers. Fix: return a read-only view, or establish a
"don't mutate" contract.

**Capturing lambda on a hot path.** A non-capturing lambda is a cached singleton — zero alloc. A
capturing lambda (closes over a local or `this`) is a new instance per evaluation. Common trap:
`map.computeIfAbsent(k, k -> compute())` allocates the lambda on *every* call including cache hits
where it is never invoked. Fix: `get` first, `computeIfAbsent` only on miss.

**`Optional` and primitive boxing.** Any `Optional*` construction allocates per call and escapes.
Autoboxing outside the JVM cache range ([-128, 127] for `Integer`/`Long`) likewise. Fix: null
checks, primitive return values, or fixed-arity overloads.

*Examples (patterns from back-test calibration):*
- Capturing lambdas allocated per-span to register per-request callbacks. Allocation
  accepted: it buys correctness (fixes a span leak). Cost nominates; the do-no-harm hierarchy
  adjudicates.
- A per-instance `TagValue` on a per-trace path — the same shape as the regex and format-string
  cases above.

---

## 4. Wrong Collection Type (SEV-2/3)

Three-step ladder: `LinkedHashMap → HashMap → POJO/record`. Lighter wins.

**`LinkedHashMap` when order isn't relied on.** ~16 B extra per entry + doubly-linked list
maintenance on every put/remove. Only justified when iteration order is required (insertion-order)
or for LRU (`accessOrder` + `removeEldestEntry`). Fix: `HashMap`.

**`HashMap` for a fixed, small, known key set.** Pays hashing, boxing, and `Entry` object overhead
per lookup. Fix: a plain record or value class — denser, EA-scalar-replaceable when non-escaping,
type-safe. A 5-line record is often *easier* to write than a map.

**Mis-sized collections.** `ArrayList` grows 1.5×; `HashMap` doubles and rehashes. Both pay
allocation + copy on growth. Fix: pre-size accurately at construction. Note: `new HashMap<>(n)`
still rehashes at 75% fill — use `HashMap.newHashMap(n)` (JDK 19+) for accurate pre-sizing.

**Concurrency choice — nominate, don't prescribe.** Replacing `ConcurrentHashMap` with `HashMap`
on a wrong concurrency judgment introduces a data race — trading a performance overhead for a
correctness bug, descending the do-no-harm hierarchy. Frame as a question ("if this map is
thread-confined, a plain collection is cheaper — verify the access pattern"), never a directive.

*Examples (patterns from back-test calibration):*
- A per-checkpoint `LinkedHashMap` collapsed to a record-like value type. The full
  three-step collapse: eliminated per-entry `Entry` overhead, boxing, and linked-list maintenance.
  ~20% throughput improvement.
- An oversized `StringBuilder(1024)` is the collection-sizing anti-pattern in a
  different form. Accurate sizing, not generous sizing, is the target.

---

## 5. Startup Latency (SEV-2)

"Once per process" treats startup costs as negligible — but that breaks for serverless (cold starts
are routine), short-lived CI jobs, and deployments that track startup time.

Flag in premain-reachable code: eager class loading, native library loads (`Native.load`),
reflection setup, config-regex compilation, eager file/network I/O, and thread creation. Fix:
defer to first-use off the hot path, or a background thread post-startup.

Startup latency and bootstrap correctness share a lens. The bootstrap constraints (no
`java.util.logging` / `java.nio` / `javax.management` in premain) are the correctness side;
startup latency is the performance side. Both route to the platform team — not as contributor
nudges.

*Examples:*
- `Native.load` inside a `write()` method. If reached on the startup path, it's a
  present startup-latency cost (loading libc + building the JNA proxy), not just a latent one.
- **Instrumentation static initializers** — any static field initialization in an `Instrumenter`
  subclass that triggers class loading or I/O on first reference is premain-reachable.

---

## 6. Domain-Adjusted Severity

**Large-denominator domains.** LLMObs, CI Visibility, DSM instrument large units of work (LLM
calls 500 ms+, Spark jobs seconds–minutes, CI test steps milliseconds–minutes). Per-"span"
CPU/alloc severity collapses. But the risk *inverts*: payload memory (large prompts, job metadata,
accumulated output) becomes SEV-1. A CPU-weighted reviewer flags the wrong things and misses the
real one.

**Streaming handlers — large-denominator rule suspends at the chunk level.** The per-call
denominator applies to costs that fire once per call. Costs inside a streaming handler fire
per-chunk — SSE, chunked HTTP, gRPC streaming can produce hundreds of events per response. An
unbounded accumulator inside a streaming handler (buffering all chunks until stream close) is
SEV-1 regardless of how slow the overall stream is.

**AppSec sub-domain split.** The WAF blocking path fires only when a block action is triggered —
genuinely cold, SILENT. The IAST taint/sink Reporter can fire frequently during an active security
scan. Treat stream usage and per-call allocations on the IAST Reporter path as SOFT-ALERT, not
cold. Do not apply "AppSec = cold" uniformly across AppSec sub-products.

*Examples:*
- An LLMObs 5 MB mapper buffer. Same "big buffer" shape as the oversized `StringBuilder`
  above, *opposite verdict*: the large-denominator (500 ms+ LLM call) absorbs the cost.
  The right call was to accept it.
- An LLMObs streaming helper that accumulated all SSE chunks into an `ArrayList` held
  until stream close. The large-denominator rule would have suppressed this; the chunk-level
  carve-out catches it: SEV-1, regardless of stream duration.

---

*Companion references in this skill: `checks.md` (the full check list + confidence/severity cost-model + Java addendum) · `example-review.md` (a worked review to calibrate output).*
