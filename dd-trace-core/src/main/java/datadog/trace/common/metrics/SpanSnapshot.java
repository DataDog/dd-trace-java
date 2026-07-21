package datadog.trace.common.metrics;

import javax.annotation.Nullable;

/**
 * Immutable per-span value posted from the producer to the aggregator thread. Carries the raw
 * inputs the aggregator needs to look up or build an {@link AggregateEntry} and update its
 * counters.
 *
 * <p>All cache-canonicalization (service-name, span-kind, peer-tag string interning) happens on the
 * aggregator thread; the producer just shuffles references.
 */
final class SpanSnapshot implements InboxItem {

  final CharSequence resourceName;
  final String serviceName;
  final CharSequence operationName;
  final CharSequence serviceNameSource;
  final CharSequence spanType;
  final short httpStatusCode;
  final boolean synthetic;
  final boolean traceRoot;
  final String spanKind;

  /**
   * Schema for {@link #peerTagValues}. {@code null} when the span has no peer tags. The schema
   * carries the names + {@link TagCardinalityHandler}s in parallel array form; {@code
   * peerTagValues} holds the per-span tag values at the same indices.
   */
  @Nullable final PeerTagSchema peerTagSchema;

  /**
   * Peer tag values captured from the span, parallel to {@code peerTagSchema.names}. A {@code null}
   * entry means the span didn't have that peer tag set. {@code null} (the whole array) when {@link
   * #peerTagSchema} is {@code null}.
   */
  @Nullable final String[] peerTagValues;

  @Nullable final String httpMethod;
  @Nullable final String httpEndpoint;
  @Nullable final String grpcStatusCode;

  /**
   * Additional metric tag values captured from the span, parallel to {@code
   * additionalTagsSchema.names}. A {@code null} entry means the span didn't have that tag set.
   * {@code null} (the whole array) when no additional tags are configured or none were set on the
   * span. Length cap is applied on the aggregator thread; the producer carries raw values only.
   */
  final @Nullable String[] additionalTagValues;

  /** Duration in nanoseconds, OR-ed with {@code ERROR_TAG} / {@code TOP_LEVEL_TAG} as needed. */
  final long tagAndDuration;

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
      String httpMethod,
      String httpEndpoint,
      String grpcStatusCode,
      String[] additionalTagValues,
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
