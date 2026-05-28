package datadog.trace.common.metrics;

/**
 * Per-span value posted from the producer to the aggregator thread. Carries the raw inputs the
 * aggregator needs to look up or build an {@link AggregateEntry} and update its counters.
 *
 * <p>Fields are mutable so this class can serve as a slot in {@link
 * datadog.trace.util.concurrent.MpscRingBuffer}: the producer claims a slot, writes its fields, and
 * publishes; the aggregator reads them. There is exactly one writer per outstanding sequence (the
 * producer that claimed it) and one reader (the aggregator), so no synchronization on fields is
 * required.
 *
 * <p>All cache-canonicalization (service-name, span-kind, peer-tag string interning) happens on the
 * aggregator thread; the producer just shuffles references.
 */
final class SpanSnapshot {

  CharSequence resourceName;
  String serviceName;
  CharSequence operationName;
  CharSequence serviceNameSource;
  CharSequence spanType;
  short httpStatusCode;
  boolean synthetic;
  boolean traceRoot;
  String spanKind;

  /**
   * Schema for {@link #peerTagValues}. {@code null} when the span has no peer tags. The schema
   * carries the names in parallel-array form; {@code peerTagValues} holds the per-span tag values
   * at the same indices.
   */
  PeerTagSchema peerTagSchema;

  /**
   * Peer tag values captured from the span, parallel to {@code peerTagSchema.names}. A {@code null}
   * entry means the span didn't have that peer tag set. {@code null} (the whole array) when {@link
   * #peerTagSchema} is {@code null}.
   */
  String[] peerTagValues;

  String httpMethod;
  String httpEndpoint;
  String grpcStatusCode;

  /** Duration in nanoseconds, OR-ed with {@code ERROR_TAG} / {@code TOP_LEVEL_TAG} as needed. */
  long tagAndDuration;

  /** No-arg constructor used by {@link MpscRingBuffer} to pre-allocate empty slots. */
  SpanSnapshot() {}

  /** Convenience constructor retained for tests that built immutable snapshots inline. */
  SpanSnapshot(
      CharSequence resourceName,
      String serviceName,
      CharSequence operationName,
      CharSequence serviceNameSource,
      CharSequence spanType,
      short httpStatusCode,
      boolean synthetic,
      boolean traceRoot,
      String spanKind,
      PeerTagSchema peerTagSchema,
      String[] peerTagValues,
      String httpMethod,
      String httpEndpoint,
      String grpcStatusCode,
      long tagAndDuration) {
    this.resourceName = resourceName;
    this.serviceName = serviceName;
    this.operationName = operationName;
    this.serviceNameSource = serviceNameSource;
    this.spanType = spanType;
    this.httpStatusCode = httpStatusCode;
    this.synthetic = synthetic;
    this.traceRoot = traceRoot;
    this.spanKind = spanKind;
    this.peerTagSchema = peerTagSchema;
    this.peerTagValues = peerTagValues;
    this.httpMethod = httpMethod;
    this.httpEndpoint = httpEndpoint;
    this.grpcStatusCode = grpcStatusCode;
    this.tagAndDuration = tagAndDuration;
  }
}
