# Client-side metrics (stats aggregator) design

This document describes the design of the **client-side metrics pipeline** that
lives under `dd-trace-core/.../common/metrics/`. The pipeline aggregates per-span
duration / count / error statistics on the tracer and sends rolled-up "client
stats" payloads to the Datadog Agent on a fixed reporting interval, so the agent
does not have to sample every span to know request rates and latencies.

Code lives in package `datadog.trace.common.metrics`.

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
| `AggregateEntry` | `AggregateEntry.java` | `Hashtable.Entry` holding the 13 UTF8 label fields plus the mutable per-bucket counters (hit count, error count, top-level count, duration sum, ok/error latency histograms). Owns the static `PropertyCardinalityHandler`s for the fixed label fields, and `Canonical` for hot-path canonicalization. |
| `PropertyCardinalityHandler` | `PropertyCardinalityHandler.java` | Per-field UTF8 interner with a max-unique-values cap. Returns a `blocked_by_tracer` sentinel `UTF8BytesString` once the cap is hit. Reset by the aggregator each cycle. |
| `TagCardinalityHandler` | `TagCardinalityHandler.java` | Same pattern as the property handler, but the cached UTF8 form is the full `tag:value` pair (peer tags are wire-encoded as `tag:value`, not just the value). |
| `SerializingMetricWriter` / `OkHttpSink` | `SerializingMetricWriter.java`, `OkHttpSink.java` | Wire serialization (MessagePack) + HTTP POST to the agent's `/v0.6/stats` endpoint. |
| `MetricsAggregatorFactory` / `NoOpMetricsAggregator` | factory + no-op | Picks the real implementation when client stats are enabled and the agent supports the endpoint, no-op otherwise. |

## Producer-side flow (`ClientStatsAggregator.publish`)

The producer holds **no shared state**. Per trace it:

1. Reads the **cached peer-aggregation schema** from a volatile field on
   `ClientStatsAggregator`:
   ```java
   PeerTagSchema schema = cachedPeerTagSchema;
   if (schema == null) { schema = bootstrapPeerTagSchema(); }
   ```
   The steady-state cost is one volatile read. The producer does **not**
   reconcile the schema against `DDAgentFeaturesDiscovery` — that's the
   aggregator thread's job, run once per reporting cycle (see
   [Aggregator-side reconcile](#aggregator-side-reconcile) below).

   The bootstrap path is a synchronized double-check that runs exactly once,
   on the very first publish. It builds the initial schema by reading
   `features.state()` *first*, then `features.peerTags()`
   (read-order matters; see the inline Javadoc on `buildPeerTagSchema`). The
   schema cache is per-`ClientStatsAggregator` instance, not static.

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
     consumer kinds → the cached peer-aggregation schema from step 1; for
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
  existing or freshly-inserted `AggregateEntry`, then the snapshot's
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

- <a id="aggregator-side-reconcile"></a>**Schema sync** (`reconcilePeerTagSchema`):
  runs on the **aggregator thread** at the start of every report cycle, via a
  hook (`onReportCycle`) passed into `Aggregator`. Fast path: compares the
  cached schema's embedded `state` hash against `features.state()` — match →
  no-op. Mismatch path: reads `features.peerTags()`; if the tag set is
  unchanged, just updates the cached schema's `state` field in place
  (preserving its warm `TagCardinalityHandler`s); if the tag set changed,
  flushes the old schema's block telemetry, builds a fresh `PeerTagSchema`,
  and writes it to the volatile `cachedPeerTagSchema`. The schema's
  `TagCardinalityHandler`s are reset alongside the property handlers in the
  same cycle.

  **Read-order note.** `DDAgentFeaturesDiscovery` exposes `peerTags()` and
  `state()` as separate accessors over its volatile state. Both
  `buildPeerTagSchema` and `reconcilePeerTagSchema` read the state hash
  *before* the tag set so that an interleaving discovery refresh leaves the
  schema "older than its names" rather than "newer", letting the next
  reconcile cycle detect the mismatch and self-heal.

## Memory and lifetime

- `AggregateEntry` counters are **not thread-safe**. They are mutated only by the
  aggregator thread.
- `AggregateTable` is **not thread-safe**. All paths (producer-side `CLEAR`,
  schedule-driven `REPORT`, drainer-driven inserts) route through the inbox.
- `Canonical` and the cardinality handlers are aggregator-thread-only.
- The cached `PeerTagSchema` lives on `ClientStatsAggregator` as a `volatile`
  field. Bootstrap (one-time, on the very first publish) is a synchronized
  double-check; thereafter only the aggregator thread mutates the field, via
  `reconcilePeerTagSchema` once per report cycle. The schema itself carries
  the `state` hash it was built from. The schema's
  `TagCardinalityHandler`s are aggregator-thread-only and are reset
  alongside the property handlers each cycle.
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
6. **Move peer-tag schema reconcile off the producer.** The producer just
   reads the volatile cached `PeerTagSchema` (steady-state: one volatile
   read). Schema reconciliation runs once per report cycle on the aggregator
   thread (`reconcilePeerTagSchema`), keyed on `DDAgentFeaturesDiscovery.state()`
   with a same-tags slow-path that preserves warm cardinality handlers across
   discovery refreshes. The cache lives on `ClientStatsAggregator`, not as
   static state on `PeerTagSchema`.
7. **Single owner of all shared state.** `disable()` routes through `CLEAR`
   rather than mutating the aggregate table directly.

### Benchmark summary

`ClientStatsAggregatorDDSpanBenchmark` (64 client-kind DDSpans per op, single
trace, real `CoreTracer` with a no-op writer):

| Variant | µs/op |
|---|---|
| master (`ConflatingMetricsAggregator`, baseline) | 6.428 |
| with `SpanSnapshot` + background aggregation | 2.454 |
| with peer-tag schema hoist | 2.410 |
| with cached span-kind ordinal + isSynthetic fix | 1.995 |

The remaining producer-thread hotspots (from JFR sampling) are tag-map
lookups for `peer.hostname` / other peer-tag values inside
`capturePeerTagValues`. A bulk peer-tag accessor on `DDSpan` would crack that
chunk further, but is a structural change beyond the current package.
