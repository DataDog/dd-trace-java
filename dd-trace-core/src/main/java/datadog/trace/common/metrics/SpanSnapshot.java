package datadog.trace.common.metrics;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.util.LongHashingUtils;
import java.util.Arrays;
import javax.annotation.Nullable;

/**
 * Per-span value posted from the producer to the aggregator thread. Carries the canonical inputs
 * the aggregator needs to look up or build an {@link AggregateEntry} and update its counters.
 *
 * <p>Fields are mutable so this class can serve as a slot in {@link
 * datadog.trace.util.concurrent.MpscRingBuffer}: the producer claims a slot, writes its fields, and
 * publishes; the aggregator reads them. There is exactly one writer per outstanding sequence (the
 * producer that claimed it) and one reader (the aggregator), so no synchronization on fields is
 * required.
 *
 * <p><b>Producer-side canonicalization and hashing.</b> String fields are canonicalized to {@link
 * UTF8BytesString} on the producer side (see {@link AggregateEntry#canonicalize}); same-content
 * strings collapse to the same canonical reference through the per-field {@code DDCache} instances
 * on {@link AggregateEntry}. The producer also precomputes {@link #keyHash} via the same chain the
 * consumer used to use. The aggregator then does identity comparisons in {@link
 * AggregateEntry#matches} and a precomputed-hash table lookup, with all the per-field work
 * distributed across producer threads where there's CPU headroom.
 */
final class SpanSnapshot {

  /** Canonical UTF-8 form of the span's resource name. {@link UTF8BytesString#EMPTY} when null. */
  UTF8BytesString resourceName;

  /** Canonical UTF-8 form of the span's service. {@link UTF8BytesString#EMPTY} when null. */
  UTF8BytesString serviceName;

  /** Canonical UTF-8 form of the span's operation name. {@link UTF8BytesString#EMPTY} when null. */
  UTF8BytesString operationName;

  /** Canonical UTF-8 form, or {@code null} when the span had no service-name source set. */
  @Nullable UTF8BytesString serviceNameSource;

  /** Canonical UTF-8 form of the span's type. {@link UTF8BytesString#EMPTY} when null. */
  UTF8BytesString spanType;

  short httpStatusCode;
  boolean synthetic;
  boolean traceRoot;

  /** Canonical UTF-8 form of the span kind. {@link UTF8BytesString#EMPTY} when not set. */
  UTF8BytesString spanKind;

  /**
   * Schema for {@link #peerTagValues}. {@code null} when the span has no peer tags. The schema
   * carries the names in parallel-array form; {@code peerTagValues} holds the per-span tag values
   * at the same indices.
   */
  @Nullable PeerTagSchema peerTagSchema;

  /**
   * Peer tag values captured from the span, parallel to {@code peerTagSchema.names}. A {@code null}
   * entry means the span didn't have that peer tag set. These are per-publish strings (typically
   * not shared across calls), so they stay as {@link String} -- canonicalizing them is more
   * expensive than the aggregator's content-comparison on a 1-3 element array.
   *
   * <p>The slot owns this array as a reusable scratch buffer; see {@code
   * ConflatingMetricsAggregator#fillSlot}.
   */
  @Nullable String[] peerTagValues;

  @Nullable UTF8BytesString httpMethod;
  @Nullable UTF8BytesString httpEndpoint;
  @Nullable UTF8BytesString grpcStatusCode;

  /** Duration in nanoseconds, OR-ed with {@code ERROR_TAG} / {@code TOP_LEVEL_TAG} as needed. */
  long tagAndDuration;

  /**
   * Hashtable bucket key, computed by the producer at the end of {@code fillSlot} via {@link
   * #computeAndSetKeyHash}. {@code AggregateTable#findOrInsert} uses this directly instead of
   * re-running the chained hash on the aggregator thread.
   */
  long keyHash;

  /** No-arg constructor used by {@link datadog.trace.util.concurrent.MpscRingBuffer}. */
  SpanSnapshot() {}

  /**
   * Computes and stores {@link #keyHash} from the snapshot's current canonical fields. The producer
   * calls this at the end of {@code fillSlot}; the aggregator then uses {@code keyHash} directly
   * for bucket lookup in {@code AggregateTable}.
   *
   * <p>Mixing identical to the prior {@code AggregateEntry.hashOf} so the bucket distribution is
   * unchanged: {@code UTF8BytesString.hashCode} returns the underlying {@code String} hash, so
   * snapshots built from {@code String} and {@code UTF8BytesString} of the same content fold to the
   * same long.
   */
  static void computeAndSetKeyHash(SpanSnapshot s) {
    long h = 0;
    h = LongHashingUtils.addToHash(h, s.resourceName);
    h = LongHashingUtils.addToHash(h, s.serviceName);
    h = LongHashingUtils.addToHash(h, s.operationName);
    h = LongHashingUtils.addToHash(h, s.serviceNameSource);
    h = LongHashingUtils.addToHash(h, s.spanType);
    h = LongHashingUtils.addToHash(h, s.httpStatusCode);
    h = LongHashingUtils.addToHash(h, s.synthetic);
    h = LongHashingUtils.addToHash(h, s.traceRoot);
    h = LongHashingUtils.addToHash(h, s.spanKind);
    // peerTagValues is gated by peerTagSchema -- the slot's array is a reusable scratch buffer
    // that may carry stale contents from a prior tag-firing publish when this publish had no
    // peer tags. Hash it only when the schema says it's meaningful.
    h = LongHashingUtils.addToHash(h, s.peerTagSchema == null ? 0 : s.peerTagSchema.namesHash);
    h =
        LongHashingUtils.addToHash(
            h, s.peerTagSchema == null ? 0 : Arrays.hashCode(s.peerTagValues));
    h = LongHashingUtils.addToHash(h, s.httpMethod);
    h = LongHashingUtils.addToHash(h, s.httpEndpoint);
    h = LongHashingUtils.addToHash(h, s.grpcStatusCode);
    s.keyHash = h;
  }
}
