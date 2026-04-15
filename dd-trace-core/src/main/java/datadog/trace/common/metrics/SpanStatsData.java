package datadog.trace.common.metrics;

/**
 * Immutable DTO carrying the minimal data needed from a CoreSpan for MetricKey construction on the
 * background aggregator thread. All fields are extracted from simple span getters (cheap
 * volatile/final field reads) on the foreground thread.
 */
final class SpanStatsData {
  final CharSequence resourceName;
  final String serviceName;
  final CharSequence operationName;
  final CharSequence serviceNameSource;
  final CharSequence spanType;
  final CharSequence spanKind;
  final short httpStatusCode;
  final boolean synthetic;
  final boolean traceRoot;
  final int error;
  final boolean topLevel;
  final long durationNano;
  // Flat array of peer tag values (already resolved to UTF8BytesString via caches)
  final Object[] peerTagValues;
  final String httpMethod;
  final String httpEndpoint;
  final String grpcStatusCode;

  SpanStatsData(
      CharSequence resourceName,
      String serviceName,
      CharSequence operationName,
      CharSequence serviceNameSource,
      CharSequence spanType,
      CharSequence spanKind,
      short httpStatusCode,
      boolean synthetic,
      boolean traceRoot,
      int error,
      boolean topLevel,
      long durationNano,
      Object[] peerTagValues,
      String httpMethod,
      String httpEndpoint,
      String grpcStatusCode) {
    this.resourceName = resourceName;
    this.serviceName = serviceName;
    this.operationName = operationName;
    this.serviceNameSource = serviceNameSource;
    this.spanType = spanType;
    this.spanKind = spanKind;
    this.httpStatusCode = httpStatusCode;
    this.synthetic = synthetic;
    this.traceRoot = traceRoot;
    this.error = error;
    this.topLevel = topLevel;
    this.durationNano = durationNano;
    this.peerTagValues = peerTagValues;
    this.httpMethod = httpMethod;
    this.httpEndpoint = httpEndpoint;
    this.grpcStatusCode = grpcStatusCode;
  }
}
