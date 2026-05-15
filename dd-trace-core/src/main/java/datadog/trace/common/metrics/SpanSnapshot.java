package datadog.trace.common.metrics;

/**
 * Immutable per-span value posted from the producer to the aggregator thread. Carries the raw
 * inputs the aggregator needs to build a {@link MetricKey} and update an {@link AggregateMetric}.
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
   * Flattened name/value pairs of peer-tag matches: {@code [name0, value0, name1, value1, ...]}.
   * {@code null} when there are no matches (the common case).
   */
  final String[] peerTagPairs;

  final String httpMethod;
  final String httpEndpoint;
  final String grpcStatusCode;

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
      String[] peerTagPairs,
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
    this.peerTagPairs = peerTagPairs;
    this.httpMethod = httpMethod;
    this.httpEndpoint = httpEndpoint;
    this.grpcStatusCode = grpcStatusCode;
    this.tagAndDuration = tagAndDuration;
  }
}
