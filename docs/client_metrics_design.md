# Client-side metrics (stats aggregator) design

This document describes the design of the **client-side metrics pipeline** that
lives under `dd-trace-core/.../common/metrics/`. The pipeline aggregates per-span
duration / count / error statistics on the tracer and sends rolled-up "client
stats" payloads to the Datadog Agent on a fixed reporting interval, so the agent
does not have to sample every span to know request rates and latencies.

Code lives in package `datadog.trace.common.metrics`.

## Overview

Tracers emit thousands of spans per second. Reporting every one to the Datadog
Agent — let alone storing them — would be expensive and mostly redundant. The
question "what's my p95 latency for `GET /users/:id` on `web-frontend` over the
last 10s" doesn't need every individual span; it needs an aggregate.

The client-stats pipeline computes those aggregates on the tracer itself and
ships rolled-up **buckets** to the agent on a fixed cadence. Each bucket is a
tuple of label values (resource, service, operation, span kind, peer tags, http
method/endpoint/status, grpc status, ...) plus a small accumulator: hit count,
error count, top-level count, duration sum, ok-latency histogram, error-latency
histogram. A bucket spans one reporting cycle (default 10 seconds); at the end
of the cycle the buckets are serialized to the agent's `/v0.6/stats` endpoint
and the in-memory accumulators are cleared.

### Goals

- **Bounded memory.** The aggregator's footprint must not grow without limit no
  matter how many distinct label combinations the workload produces, or how
  high the span throughput is.
- **No producer-thread contention.** Application threads that complete a span
  shouldn't block on a lock or do meaningful work beyond cheap field
  extraction. The tracer is a guest in the application's process; it must not
  show up as overhead.
- **Correctness under reset.** Cardinality budgets and histograms are dropped
  every reporting cycle. Mid-cycle drops and agent-downgrade clears can't
  corrupt the aggregate table or fragment a single logical bucket.
- **Stable wire format.** The bucket payload matches the existing `/v0.6/stats`
  schema. This is a re-implementation of an existing protocol, not a protocol
  change.

### Architecture in one paragraph

A single **aggregator thread** owns every piece of mutable state — the bucket
table, the per-field cardinality budgets, the histograms. Application threads
build a small immutable **span snapshot** per metrics-eligible span and post it
to a bounded MPSC inbox. The aggregator drains the inbox, **canonicalizes**
each snapshot's label values through cardinality-capped UTF8 interners, hashes
the canonical form, and finds-or-inserts a bucket. A scheduled signal flushes
buckets to the agent every reporting interval; the cardinality budgets are
reset in lockstep with the flush.

### Three rules

1. **Producer threads never touch shared state.** They build a snapshot and
   hand it off through the inbox. The aggregator does all the heavy work
   (canonicalization, hashing, lookup, accumulator updates).
2. **Cardinality is capped per field, per cycle.** Each label field has its
   own budget; overflow values collapse to a single `blocked_by_tracer`
   sentinel so the bucket table can never grow past
   `maxAggregates × (sum of per-field budgets)` distinct combinations.
3. **Aggregation happens on canonical UTF8 forms.** Two snapshots that disagree
   only on representation (same content delivered once as a `String`, once as
   a `UTF8BytesString`) collapse to the same bucket because hashing and
   matching happen *after* canonicalization.

### Trade-offs

- **One snapshot allocation per metrics-eligible span.** ~100 bytes per
  snapshot; cheap individually but a meaningful share of producer allocation
  at high span throughput. Snapshots could be pooled or replaced with a
  struct-of-arrays inbox; neither is currently worth the complexity.
- **Cap-overrun drops the new key, not LRU.** When the bucket table is at
  capacity and no entry is stale enough to evict, an incoming snapshot for a
  new label combination is dropped (and reported via
  `onStatsAggregateDropped`). This protects the steady-state workload from a
  burst of new keys that would otherwise displace established buckets.
- **One aggregator thread.** The whole consumer side is single-threaded by
  design — locks, races, and visibility footguns are confined to the producer
  → inbox handoff. If the producer rate is sustainedly higher than the
  aggregator can drain, the inbox fills and snapshots are dropped
  (`onStatsInboxFull`).
- **Fixed bucket table.** The hashtable's bucket array is sized once at
  startup from `maxAggregates`. No dynamic resizing; entries beyond the cap
  trigger the drop-new-key path above.

## High-level shape

```
                producer thread(s)                           aggregator thread
                                                  inbox
   trace ─▶ ClientStatsAggregator.publish(trace) ──MPSC──▶ Aggregator.run
              │                                              │
              │ per metrics-eligible span                    │ Drainer.accept
              │                                              │
              │ allocates one SpanSnapshot                   ▼
              │ (immutable, ~15 refs)                      AggregateTable.findOrInsert
              │                                              │
              │ inbox.offer(snapshot)                        │  canonicalize → hash
              └────────────────────────────────────▶         │  → lookup or insert
                                                             │
                                  scheduled REPORT signal ──▶│
                                                             │ Aggregator.report
                                                             │  → MetricWriter.add(entry)
                                                             │  → OkHttpSink (HTTP POST)
                                                             │  → reset cardinality handlers
```

Three rules govern the design:

1. **The producer never touches shared state.** The hot path on the application
   thread builds an immutable `SpanSnapshot` and offers it to a bounded MPSC
   queue. No locks, no maps, no hashing of the metric key.
2. **The aggregator thread is the sole writer of every shared structure.** The
   aggregate table, the cardinality handlers, the metric writer state — all of
   them are accessed only from that thread. Control operations (clear, report,
   stop) are themselves enqueued as `SignalItem`s so they serialize with data.
3. **Cardinality is bounded.** Per-field handlers cap the unique values; once a
   field's budget is exhausted, overflow values collapse into a single
   `blocked_by_tracer` sentinel so the aggregate table can't blow up.

## Component map

| Component | File | Role |
|---|---|---|
| `ClientStatsAggregator` | `ClientStatsAggregator.java` | Producer facade. Decides which spans are eligible, builds `SpanSnapshot`s, offers them to the inbox. Also owns the agent-feature check, the scheduled report timer, and the agent-downgrade handler. |
| `SpanSnapshot` | `SpanSnapshot.java` | Immutable, allocation-pooled-by-GC value posted from producer → aggregator. Carries raw label fields plus a duration word with `TOP_LEVEL` / `ERROR` bits OR-ed in. |
| `PeerTagSchema` | `PeerTagSchema.java` | Parallel `String[] names` + `TagCardinalityHandler[] handlers` describing the peer-aggregation tags in effect. One singleton for internal-kind spans; one volatile "current" schema for client/producer/consumer spans, refreshed from `DDAgentFeaturesDiscovery.peerTags()`. |
| `Aggregator` | `Aggregator.java` | Consumer thread `Runnable`. Drains the inbox; dispatches `SpanSnapshot`s into `AggregateTable`; processes signals (`REPORT`, `CLEAR`, `STOP`); calls the writer on report. |
| `AggregateTable` | `AggregateTable.java` | Hashtable-backed store keyed on the canonicalized labels. Owns a single reusable `Canonical` scratch buffer. Handles cap-overflow by evicting one stale entry or rejecting new ones. |
| `AggregateEntry` | `AggregateEntry.java` | `Hashtable.Entry` holding the 13 UTF8 label fields + the mutable `AggregateMetric`. Owns the static `PropertyCardinalityHandler`s for the fixed label fields, and `Canonical` for hot-path canonicalization. |
| `AggregateMetric` | `AggregateMetric.java` | Per-bucket accumulator: hit count, error count, top-level count, duration sum, ok/error latency histograms. Single-threaded; cleared each report. |
| `PropertyCardinalityHandler` | `PropertyCardinalityHandler.java` | Per-field UTF8 interner with a max-unique-values cap. Returns a `blocked_by_tracer` sentinel `UTF8BytesString` once the cap is hit. Reset by the aggregator each cycle. |
| `TagCardinalityHandler` | `TagCardinalityHandler.java` | Same pattern as the property handler, but the cached UTF8 form is the full `tag:value` pair (peer tags are wire-encoded as `tag:value`, not just the value). |
| `SerializingMetricWriter` / `OkHttpSink` | `SerializingMetricWriter.java`, `OkHttpSink.java` | Wire serialization (MessagePack) + HTTP POST to the agent's `/v0.6/stats` endpoint. |
| `MetricsAggregatorFactory` / `NoOpMetricsAggregator` | factory + no-op | Picks the real implementation when client stats are enabled and the agent supports the endpoint, no-op otherwise. |

## Producer-side flow (`ClientStatsAggregator.publish`)

The producer holds **no shared state**. Per trace it:

1. Snapshots the current peer-aggregation schema **once per trace** (not per
   span):
   ```java
   PeerTagSchema peerAggSchema = peerAggSchema(features.peerTagsRevision());
   ```
   `peerAggSchema(...)` reads a `volatile long` revision held on the
   aggregator and compares it to the value the cached `PeerTagSchema` was
   built from. Match → return the cached schema (the common case, since
   `peerTagsRevision()` only bumps when `DDAgentFeaturesDiscovery` observes a
   peer-tag set that doesn't equal the previous one). Mismatch → take a
   monitor on the aggregator, rebuild via `PeerTagSchema.of(names)`, and
   publish the new schema + revision. The steady-state cost is one volatile
   read + one long compare.

2. Iterates the trace; for each metrics-eligible span:

   - **Eligibility** (`shouldComputeMetric`):
     ```java
     (measured || isTopLevel || isKind(SERVER|CLIENT|PRODUCER|CONSUMER))
       && longRunningVersion <= 0
       && durationNano > 0
     ```
     `isMeasured` / `isTopLevel` are flag reads on `DDSpanContext`; `isKind`
     reads the **cached `byte` span-kind ordinal** through a `SpanKindFilter`
     bitmask test — no tag-map lookup.

   - **Resource-name ignore-list** breaks out of the trace early; the entire
     trace is dropped on a match.

   - **Picks the peer-tag schema** (`peerTagSchemaFor`): for client/producer/
     consumer kinds → `peerAggSchema` (already synced for this trace); for
     internal-kind spans → `PeerTagSchema.INTERNAL` (single `base.service`
     entry); otherwise `null`.

   - **Captures peer-tag *values***, not pairs: walks `schema.names` and pulls
     `unsafeGetTag(name)` for each, into a parallel `String[]`. Names + handlers
     are the schema's job; the producer only carries raw values. Returns `null`
     when no peer tags are set, in which case the schema reference is dropped
     too so the consumer doesn't loop over an all-null array.

   - **Builds and offers** a `SpanSnapshot` to the MPSC inbox. The span-kind
     string is taken from `CoreSpan.getSpanKindString()`, which DDSpan
     overrides to resolve via the cached byte ordinal through a small lookup
     array — **no tag-map lookup**. Origin equality uses `contentEquals`.
     `httpMethod` / `httpEndpoint` are only fetched when
     `traceClientStatsEndpoints=true`; `grpcStatusCode` only when span type is
     `rpc`.

   - On inbox-full: the snapshot is dropped and `healthMetrics.onStatsInboxFull()`
     fires. The producer never blocks.

3. Reports `healthMetrics.onClientStatTraceComputed(counted, total, dropped)`.

   `forceKeep` is the only signal returned upward — `true` if any of the
   trace's metrics-eligible spans had errors, so the trace writer keeps the
   raw trace too.

### Why the producer is lean

The cumulative cost of running these checks on every finished span is the
single biggest concern. The producer deliberately avoids:

- locking or synchronization of any kind on the hot path,
- hashing the metric key (deferred to the aggregator thread),
- map / cache lookups for label canonicalization (deferred),
- tag-map lookups when a span carries the relevant information on the context
  itself (`span.kind` via the cached byte ordinal; `isMeasured`, `isTopLevel`
  via flag reads),
- allocation beyond the `SpanSnapshot` itself and a single `String[]` for peer
  tag values when any are present.

## Aggregator-side flow (`Aggregator.run`)

A single agent thread runs the `Aggregator.run` loop. The thread drains the
inbox via `inbox.drain(drainer)`; when the queue is empty it sleeps
`DEFAULT_SLEEP_MILLIS` (10 ms) and retries. The Drainer dispatches by item
type:

- `SpanSnapshot` → `AggregateTable.findOrInsert(snapshot)` returns either an
  existing or freshly-inserted `AggregateMetric`, then the snapshot's
  `tagAndDuration` is recorded. If the table is at capacity and no stale entry
  can be evicted, `healthMetrics.onStatsAggregateDropped()` fires.

- `ReportSignal` → on the scheduled cadence (the default report interval is
  10 s; configurable via `tracerMetricsMaxAggregates` / reporting interval),
  `Aggregator.report`:
  1. Expunges entries with `hitCount == 0` (stale).
  2. If anything remains, opens a bucket via `MetricWriter.startBucket(...)`,
     walks `AggregateTable.forEach`, writes each entry, clears its metric.
  3. Calls `MetricWriter.finishBucket()` (which may do I/O and block).
  4. **Resets all cardinality handlers** so the next interval starts with a
     fresh budget. Existing entries keep their previously-issued UTF8
     references, and matching is by content-equality, so canonicalizing a
     post-reset snapshot against an existing entry still resolves to the
     same bucket.

- `ClearSignal` → drops the aggregate state. The downgrade handler
  (`onEvent(DOWNGRADED, ...)`) offers `CLEAR` to the inbox rather than calling
  `clearAggregates()` directly, so the aggregator thread remains the sole
  writer of the table.

- `StopSignal` → final report + thread exit.

## The canonical-key trick (cardinality-safe deduplication)

The lookup hash is computed from the **canonicalized** label fields, not the
raw `SpanSnapshot` fields. This is the property that makes
cardinality-blocking actually save space:

```java
// AggregateTable.findOrInsert
canonical.populate(snapshot);   // runs every field through its handler
long keyHash = canonical.keyHash;
int bucketIndex = Hashtable.Support.bucketIndex(buckets, keyHash);
for (Hashtable.Entry e = buckets[bucketIndex]; e != null; e = e.next()) {
  if (e.keyHash == keyHash) {
    AggregateEntry candidate = (AggregateEntry) e;
    if (canonical.matches(candidate)) {
      return candidate.aggregate;
    }
  }
}
// miss → toEntry, splice into bucket head
```

`Canonical.populate` runs each label field through its
`PropertyCardinalityHandler` (or `TagCardinalityHandler` for peer tags). Once a
handler's working set is full, **every subsequent unique value resolves to the
same `UTF8BytesString` sentinel** — so the hash computed from the canonical
form is identical for all blocked values. They land in the same bucket and
merge into one `AggregateEntry` rather than fragmenting into N entries.

The `Canonical` scratch buffer is reused per `findOrInsert` call. On a hit,
nothing is allocated. On a miss, `toEntry` snapshots the buffer's references
into a fresh entry; the buffer is overwritten on the next call.

### Hash chain (no varargs)

`AggregateEntry.hashOf` uses chained primitive calls into
`LongHashingUtils.addToHash(long, T)` rather than a varargs `addToHash(long,
Object...)`. This avoids the `Object[]` allocation and boxing of the primitive
fields (`httpStatusCode`, `synthetic`, `traceRoot`) that varargs would force.

## Reporting cadence and cardinality reset

Two distinct cadences:

- **Reporting interval** (default 10 s): when the report timer fires,
  `ReportTask` calls `report()` which `inbox.offer(REPORT)`. The aggregator
  drains up to that signal, then writes the bucket and resets the cardinality
  handlers. The handlers reset *every reporting cycle*, so the per-field
  budgets refresh.

- **Schema sync**: `ClientStatsAggregator.peerAggSchema(long)` runs on the
  producer thread per trace, keyed on `DDAgentFeaturesDiscovery.peerTagsRevision()`.
  The cached schema is replaced when remote-config reconfigures the peer-tag
  set (i.e., when the revision bumps). The schema's
  `TagCardinalityHandler`s are reset on the aggregator thread each report
  cycle via a hook passed into `Aggregator`.

## Memory and lifetime

- `AggregateEntry`'s per-bucket counters + histograms are **not thread-safe**;
  they are mutated only by the aggregator thread.
- `AggregateTable` is **not thread-safe**. All paths (producer-side `CLEAR`,
  schedule-driven `REPORT`, drainer-driven inserts) route through the inbox.
- `Canonical` and the cardinality handlers are aggregator-thread-only.
- The cached `PeerTagSchema` lives on `ClientStatsAggregator` as a `volatile`
  field paired with the `peerTagsRevision` it was built from; rebuild is
  guarded by a monitor on the aggregator instance. The schema's
  `TagCardinalityHandler`s themselves are aggregator-thread-only and are
  reset alongside the property handlers each cycle.
- Entries retain their `UTF8BytesString` references across handler resets;
  matches via content-equality so post-reset snapshots still resolve.
- Cap: `tracerMetricsMaxAggregates` bounds table size. Cap-overrun policy:
  evict one stale entry (`hitCount == 0`) or drop the new data point.

## Health metrics

The producer reports per-trace stats via `HealthMetrics`:

- `onClientStatTraceComputed(counted, totalSpans, dropped)` — per `publish`.
- `onStatsInboxFull()` — when the MPSC queue rejects an offer.
- `onClientStatPayloadSent()` / `onClientStatDowngraded()` /
  `onClientStatErrorReceived()` — on agent-side outcomes.
- `onStatsAggregateDropped()` — when the aggregator thread can't fit a new
  entry.

## Failure modes

| Failure | Effect |
|---|---|
| Inbox full | Snapshot dropped, `onStatsInboxFull` increments, producer continues. |
| Agent unavailable / errors | `OkHttpSink` reports `BAD_PAYLOAD` / `ERROR`; metric reporting continues. |
| Agent downgrade (no /v0.6/stats) | `disable()` offers `CLEAR` to the inbox; the aggregator wipes its table. Producer's `features.supportsMetrics()` returns false on subsequent calls, so new snapshots are not built. |
| Aggregate table full, no stale entry | New snapshot dropped, `onStatsAggregateDropped` increments. Existing entries continue to accumulate. |
| Cardinality budget exhausted | Overflow values canonicalize to a `blocked_by_tracer` sentinel and merge into one bucket. Total entry count stays bounded by `maxAggregates`. |
| Producer throws mid-trace | Caught by the writer's normal error path; `onClientStatTraceComputed` is not called for that trace. |

## Behavior under adversarial load

A useful stress test (captured as `AdversarialMetricsBenchmark`): 8 producer
threads call `publish` in a tight loop with **unique** `(service, operation,
resource, peer.hostname)` per op, random durations across 30 orders of
magnitude, and random `error` / `topLevel` flags. Every cardinality dimension
saturates within milliseconds.

### What "OOM the metrics subsystem" would look like

A successful attack would either grow the aggregator's heap unboundedly, or
back up the producer so a synchronous structure (cache, map) grew with each
unique label combination. The current shape rules both out by construction:

- **Inbox is a fixed-size MPSC queue.** Overflow returns `false` from
  `offer` and the producer drops the snapshot via `onStatsInboxFull`.
  The snapshot becomes garbage immediately; no queue growth.
- **`AggregateTable` is a fixed-size bucket array.** Insertion when the
  table is full triggers an evict-stale pass (one entry with
  `hitCount == 0`); if that fails the snapshot is dropped via
  `onStatsAggregateDropped`. The table never resizes.
- **Cardinality handlers are flat open-addressed arrays.** Overflow values
  canonicalize to the shared `blocked_by_tracer` sentinel — same hash,
  same bucket, merged in. No node allocations, no rehash.
- **Histograms use `CollapsingLowestDenseStore(1024)`.** Bucket array
  caps at ~8 KB per histogram. Worst case at full table cap: 2048 entries
  × 2 histograms × ~8 KB ≈ 32 MB. That's the headline upper bound.
- **Empty error histograms aren't allocated until first error
  recorded.** Entries that never error keep `errorLatencies = null`,
  saving the wrapper allocation.

### Measured behavior (1f × 1wi × 3i × 15s, 8 threads each side)

| | master (`ConflatingMetricsAggregator`) | this design (`ClientStatsAggregator`) |
|---|---:|---:|
| Iteration 1 throughput | 1,506,007 ops/s | ~5,853,917 ops/s |
| Iteration 2 throughput | 1,255,258 ops/s | ~5,800,000 ops/s |
| Iteration 3 throughput | **410,097 ops/s** (-73%) | ~5,853,917 ops/s (stable) |
| GC time / 15 s wall | iter 1: 8.7 s — iter 2: 9.8 s — iter 3: **18.6 s** (multi-thread GC saturation) | ~150 ms total |
| Producer allocation | ~1,108 B/op | ~823 B/op |
| Aggregator thread state at end | "Skipped metrics reporting because the queue is full" + thread idle waiting for inbox | Continuously draining; ~13M snapshots/sec consumed |
| Inbox-full drops | (no counter on master) | ~139 M dropped over 45 s, all reported via `onStatsInboxFull` |
| Aggregate-table drops | 0 | 0 |

### Why master degrades

On master, the producer does **everything** synchronously on the calling
thread: `MetricKey` canonicalization, `DDCache` lookups for each label field,
`LRUCache` insertion. There is no queue between producer and consumer
— there *is* no consumer thread for the storage work, only for the
periodic report. So a 1,108 B/op allocation rate × 8 threads × ~1.5 M ops/s
generates ~13 GB/sec of garbage on the same thread that has to keep up with
incoming spans. The young gen fills, then survivor, then old gen, then full
GC. By iteration 3 the JVM is spending more than its wall-clock budget on
GC (multiple concurrent GC threads, summed > 15 s during a 15 s window) and
throughput collapses 73 %.

### Why this design holds

The producer/consumer split converts allocation pressure into **backpressure
at the inbox boundary**. The producer's per-op work is just "allocate one
`SpanSnapshot`, set a few `volatile` refs, `inbox.offer`, return." On
overflow, `offer` returns `false` and the snapshot is dropped on the spot —
no waiting, no allocation amplification. The aggregator thread runs at
its natural rate (~13 M snapshots/sec on the test machine), and the gap
between producer and consumer becomes the drop rate, not heap growth.
`onStatsInboxFull` makes that gap observable so operators can size
`tracerMetricsMaxPending` and `tracerMetricsMaxAggregates` for their
workload.

Net: under adversarial input the new design absorbs what it can compute
meaningfully and drops what it can't, with both numbers exposed via health
metrics. The bounded-design properties hold to the ~32 MB worst-case
ceiling described above.

## Why the redesign (history)

The pipeline was previously `ConflatingMetricsAggregator` with:

- producer-side `MetricKey` construction (string-canonicalization on the hot
  path),
- a `LRUCache` of `MetricKey → AggregateMetric`,
- per-tag `DDCache` instances for canonicalization (one per label field),
- early computation of `tag:value` peer pairs on the producer thread.

The current `ClientStatsAggregator` shape was motivated by JMH benchmarks that
showed the producer dominating CPU time. The major shifts:

1. **Move all canonicalization off the producer.** Producer just shuffles
   references into a `SpanSnapshot`.
2. **Replace `MetricKey` with inlined fields on `AggregateEntry`.** Removes a
   per-snapshot allocation; lets us own the hash code on the entry itself.
3. **Replace the `LRUCache` with a `Hashtable`** keyed on canonicalized labels.
   Hash is computed once per insert/lookup; chained primitive hashing avoids
   boxing.
4. **Replace per-tag `DDCache`s with per-field `PropertyCardinalityHandler`s**
   that share a `blocked_by_tracer` sentinel for cardinality overflow. Reset
   each reporting cycle.
5. **Capture peer-tag values, not pairs.** Tag-name + handler live on
   `PeerTagSchema`; the producer carries values in a parallel `String[]`. The
   aggregator does the `tag:value` interning via `TagCardinalityHandler` on
   its own thread.
6. **Sync peer-tag schema once per trace.** The producer reads
   `features.peerTagsRevision()` and compares it to the revision the cached
   `PeerTagSchema` was built from; the steady-state cost is one volatile read
   and one long compare. The cache lives on `ClientStatsAggregator`, not as
   static state on `PeerTagSchema`.
7. **Single owner of all shared state.** `disable()` routes through `CLEAR`
   rather than mutating the aggregate table directly.

### Benchmark summary

Four JMH benchmarks cover the producer pipeline at different angles:

- `ClientStatsAggregatorBenchmark` — 64 SimpleSpans per op, identical labels
  (consumer-side cache hit path).
- `ClientStatsAggregatorDDSpanBenchmark` — same as above but real `DDSpan`
  via `CoreTracer`; exercises the production `isKind` / cached span-kind
  ordinal path.
- `ClientStatsAggregatorMissPathBenchmark` — pool of 4096 single-span
  traces with unique labels; exercises miss + insert + handler saturation.
- `AdversarialMetricsBenchmark` — 8 threads, unique labels per op, random
  durations + error flags; pushes every bound to its limit (see
  [Behavior under adversarial load](#behavior-under-adversarial-load)).

Optimization progression on the DDSpan benchmark:

| Variant | µs/op |
|---|---|
| master (`ConflatingMetricsAggregator`, baseline) | 6.428 |
| with `SpanSnapshot` + background aggregation | 2.454 |
| with peer-tag schema hoist | 2.410 |
| with cached span-kind ordinal + isSynthetic fix | 1.995 |

On the producer-bound miss-path benchmark (single-span trace, unique
labels), `ClientStatsAggregatorMissPathBenchmark` measures **0.057 µs/op +
96 B/op** vs master's **0.305 µs/op + 399 B/op** — 5.3× faster, 4.2× less
producer-thread allocation per metrics-eligible span.

The remaining producer-thread hotspots (from JFR sampling) are tag-map
lookups for `peer.hostname` / other peer-tag values inside
`capturePeerTagValues`. A bulk peer-tag accessor on `DDSpan` would crack that
chunk further, but is a structural change beyond the current package.
