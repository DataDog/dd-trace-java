# Memory and retention

Checks #3, J5, J6, J13. Unbounded memory is P1 — flag aggressively.

A collection that grows without a bound can kill the customer's container. The tracer shares the
application heap — there is no isolation. A false positive here is cheap insurance against a
container kill. Flag aggressively.

## Checks

- **#3 — Unbounded memory / collection** — *a cache/map/collection with no size+byte bound, or
  keyed by a high-cardinality input (per-request data, raw strings)* — flag-with-confidence
  (unboundedness is structurally visible) — **P1 (OOM / container-kill — top severity)** — fix:
  bound by count *and* bytes; or don't cache high-cardinality inputs (opt out).
- **J5 — Cardinality-sensitive aggregator** *(domain-specialized #3)*. Some structures are
  invisible to the generic "unbounded collection" check because the risk is *cardinality*, not raw
  size: a config- or user-driven value (tag key, resource name, HTTP URL) feeding a
  **cardinality-sensitive aggregator** (e.g. the conflating metrics aggregator — each unique label
  combination = one aggregate; a `maxAggregates` cap bounds OOM but high-cardinality input
  *thrashes* it: constant eviction, garbled metrics). flag-with-confidence when config/user-driven
  values reach an aggregator with a per-key budget — **P1** (same class as unbounded memory:
  correctness + heap impact). Fix: bound the source cardinality before it enters the aggregator, or
  use sentinel substitution for over-cap values. This surfaced on merged production code more than
  once in back-test calibration — the capstone pattern where the bot's value concentrates.
- **J6 — Reference strengthening in weak-cache scans** *(refines #1, #2)*. Calling
  `WeakReference.get()` (or `SoftReference.get()`) inside a cache-probe loop to identify the
  referent **strengthens** the reference — the returned strong ref keeps the object alive until it
  goes out of scope, defeating the purpose of the weak reference. Pattern to flag: a loop over a
  weak-ref cache that calls `.get()` for identity/equality comparison on every slot probed. Fix:
  store a stable key (e.g. `System.identityHashCode(context)`) in the wrapper at construction time;
  compare the key first (plain int, no strengthening); call `.get()` only on a key match (the right
  moment — you're about to use the referent anyway) or to detect eviction (`get() == null`).
  **flag-with-confidence** when `.get()` appears inside a probe loop on a hot path — P3.
- **J13 — Large retained `byte[]` → `ChunkedBuffer`** *(refines #1, J4)*. A retained buffer
  (writer buffer, field, static) over 512 KiB is humongous on G1 with 1 MB regions and adds to the
  app's live set for the tracer's lifetime: **flag-with-confidence, P2**, worse if it's
  allocated while its product is off. 256–512 KiB, or allocated per flush: **flag-as-measure,
  P3**. Fix: `ChunkedBuffer` / `ChunkedStreamingBuffer` (APMLP-2114); off-heap isn't a fix, it
  only moves the bytes to native memory. Trap: a power-of-two 512 KiB size is humongous because of
  its 16-byte header, and `GrowableBuffer` rounds up to a power of two.

## Patterns

**Raw unbounded cache.** No size or byte bound, keyed by data that grows with load (URL, SQL,
resource names). Fix: `DDCaches.newFixedSizeWeightedCache(n, weigher, maxBytes)`.

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
> correct choice — caching it would be the worse P1. Flag a cache *keyed by* high-cardinality
> data; never flag the decision not to cache.

*Examples (patterns from back-test calibration):*
- A `LoadingCache` keyed by URL and SQL text with no size or byte limit — the capstone
  pattern: unbounded growth tied directly to traffic volume.
- A DSM pathway hash that included a timestamp field, making the aggregator's slot count
  grow without bound. Removing the timestamp from the key is a P1 prevention.
