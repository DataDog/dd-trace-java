# Memory and retention

Checks #3, J5, J6, J13.

The tracer shares the application's heap; there is no isolation. A collection that grows without
a bound can get the customer's container killed. So memory checks are the one place to flag
aggressively: a false positive costs a reviewer a minute, a missed one costs an outage.

## #3 Unbounded memory or collection

- **Flag:** a cache, map or collection on a reachable path that is missing a count bound or a byte
  bound (it needs both, and a byte bound must count the keys as well as the values), or that is
  keyed by input whose cardinality grows with traffic (URL, SQL text, resource
  name, per-request values). Also flag a change that removes an existing cap without adding
  another bound, including caps on collections that Remote Config, user input or another external
  source can grow; ask whether the removal is intended.
- **Not when:** the change decides *not* to cache open-cardinality input (raw SQL with inline
  literals, per-request strings). Not caching is correct there; caching would be the worse
  problem. Flag a cache keyed by such data, never the absence of one.
- **Confidence / level:** flag-with-confidence (a missing bound is visible in the code).
  - P1 when nothing bounds the number of entries.
  - P2 when the count is bounded but the bytes are not, or the byte bound leaves out the keys.
    Retention is then capped at the count times the largest entry, so it can be large but not
    unbounded.
- **Fix:** bound by count and by bytes, for example
  `DDCaches.newFixedSizeWeightedCache(n, weigher, maxBytes)`. Or don't cache the input at all.
- **Why:** growth tied to traffic ends in an OOM or a container kill, not a slowdown.
- **Example:** a `LoadingCache` keyed by URL and SQL text with no size or byte limit.

## J5 Cardinality-sensitive aggregator

- **Flag:** a config- or user-driven value (tag key or value, resource name, HTTP URL) that feeds
  an aggregator with a per-key budget, such as the conflating metrics aggregator, where each
  distinct key combination is its own aggregate. Also flag any value with open cardinality used as
  part of an aggregation key or a propagated hash: values that change per message (timestamp,
  offset, correlation id) and values that come from the data (object or file names such as S3
  keys, URLs, query text). Check which fields a changed hash actually covers; a loop bound or
  index list can pull in a field its comment says is excluded.
- **Not when:** the value comes from a small fixed set, or is bounded before it reaches the
  aggregator.
- **Confidence / level:** flag-with-confidence. P1.
- **Fix:** bound the source cardinality before it enters the aggregator, replace over-cap values
  with a sentinel, drop the per-message dimension, or replace raw timestamps with time buckets.
- **Why:** a nominal cap such as `maxAggregates` prevents the OOM, but high-cardinality input
  makes it evict constantly, so the metrics come out wrong. #3 misses this because the
  collection does have a size bound.
- **Examples:** a DSM pathway hash that included a timestamp, so the aggregator gained a new slot
  for every message; a DSM pathway hash whose field loop covered a data-set name (an S3 object
  key), so every downstream group split per object.

## J6 Reference strengthening in weak-cache scans

- **Flag:** a loop over a weak- or soft-reference cache that calls `.get()` on each slot it probes
  to compare the referent, on a hot path.
- **Not when:** `.get()` is called only after a cheap key comparison matches, or only to detect
  eviction (`get() == null`).
- **Confidence / level:** flag-with-confidence. P3.
- **Fix:** store a stable key (for example `System.identityHashCode(context)`) in the wrapper when
  it is created. Compare that int first, and call `.get()` only on a key match.
- **Why:** every `.get()` returns a strong reference that keeps the referent alive while it is in
  scope, so probing every slot defeats the weak reference.

## J13 Large retained `byte[]`

- **Flag:** a retained buffer (writer buffer, field, static) larger than 512 KiB.
  Flag-as-measure for 256–512 KiB, or for a large buffer allocated on every flush.
- **Not when:** the buffer is short-lived and below 256 KiB.
- **Confidence / level:** over 512 KiB, flag-with-confidence, P2, raised if it is allocated while
  its product is off. 256–512 KiB or per flush: flag-as-measure, P3.
- **Fix:** `ChunkedBuffer` / `ChunkedStreamingBuffer`. Moving the bytes off-heap is not a fix; it
  only moves them to native memory.
- **Why:** with G1's 1 MB regions, an array over half a region is "humongous" and stays in the
  application's live set for the tracer's lifetime.
- **Trap:** a power-of-two 512 KiB array is already humongous because of its 16-byte header, and
  `GrowableBuffer` rounds sizes up to a power of two.
