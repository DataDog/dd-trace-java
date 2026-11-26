package datadog.trace.common.metrics;

import javax.annotation.Nullable;

/**
 * Per-span value the aggregator looks up or builds an {@link AggregateEntry} from. Carries the raw
 * inputs the aggregator needs to find the entry and update its counters.
 *
 * <p>Built two ways. When the producer captures, it posts an immutable snapshot per span to the
 * aggregator thread. When the aggregator thread captures (see {@link TraceCapture}), it refills one
 * reused instance per span, which {@link AggregateTable#findOrInsert} only reads: it copies what it
 * keeps into its own canonical buffer.
 *
 * <p>All cache-canonicalization (service-name, span-kind, peer-tag string interning) happens on the
 * aggregator thread; the producer just shuffles references.
 */
final class SpanSnapshot implements InboxItem {

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
   * carries the names + {@link TagCardinalityHandler}s in parallel array form; {@code
   * peerTagValues} holds the per-span tag values at the same indices.
   */
  @Nullable PeerTagSchema peerTagSchema;

  /**
   * Peer tag values captured from the span, parallel to {@code peerTagSchema.names}. A {@code null}
   * entry means the span didn't have that peer tag set. {@code null} (the whole array) when {@link
   * #peerTagSchema} is {@code null}.
   */
  @Nullable String[] peerTagValues;

  @Nullable String httpMethod;
  @Nullable String httpEndpoint;
  @Nullable String grpcStatusCode;

  /**
   * Additional metric tag values captured from the span, parallel to {@code
   * additionalTagsSchema.names}. A {@code null} entry means the span didn't have that tag set.
   * {@code null} (the whole array) when no additional tags are configured or none were set on the
   * span. Length cap is applied on the aggregator thread; the producer carries raw values only.
   */
  @Nullable String[] additionalTagValues;

  /** Duration in nanoseconds, OR-ed with {@code ERROR_TAG} / {@code TOP_LEVEL_TAG} as needed. */
  long tagAndDuration;

  /** A blank instance for the aggregator thread to refill with {@link #set}. */
  SpanSnapshot() {}

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
      @Nullable PeerTagSchema peerTagSchema,
      @Nullable String[] peerTagValues,
      @Nullable String httpMethod,
      @Nullable String httpEndpoint,
      @Nullable String grpcStatusCode,
      @Nullable String[] additionalTagValues,
      long tagAndDuration) {
    set(
        resourceName,
        serviceName,
        operationName,
        serviceNameSource,
        spanType,
        httpStatusCode,
        synthetic,
        traceRoot,
        spanKind,
        peerTagSchema,
        peerTagValues,
        httpMethod,
        httpEndpoint,
        grpcStatusCode,
        additionalTagValues,
        tagAndDuration);
  }

  /** Overwrites every field; see the constructor. */
  void set(
      CharSequence resourceName,
      String serviceName,
      CharSequence operationName,
      CharSequence serviceNameSource,
      CharSequence spanType,
      short httpStatusCode,
      boolean synthetic,
      boolean traceRoot,
      String spanKind,
      @Nullable PeerTagSchema peerTagSchema,
      @Nullable String[] peerTagValues,
      @Nullable String httpMethod,
      @Nullable String httpEndpoint,
      @Nullable String grpcStatusCode,
      @Nullable String[] additionalTagValues,
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
    this.additionalTagValues = additionalTagValues;
    this.tagAndDuration = tagAndDuration;
  }
}
