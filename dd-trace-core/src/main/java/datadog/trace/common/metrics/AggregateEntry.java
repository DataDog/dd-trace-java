package datadog.trace.common.metrics;

import datadog.trace.util.Hashtable;
import datadog.trace.util.LongHashingUtils;
import java.util.Arrays;
import java.util.Objects;

/**
 * Hashtable entry pairing the raw {@link SpanSnapshot} key fields with their canonical {@link
 * MetricKey} (built once on miss) and the mutable {@link AggregateMetric}.
 *
 * <p>Lookups compare the snapshot's raw fields against the entry's stored copies, so the consumer
 * never has to build a {@link MetricKey} just to do a HashMap lookup. The {@code MetricKey} field
 * is retained because the serializer ({@link MetricWriter#add}) needs it at report time.
 */
final class AggregateEntry extends Hashtable.Entry {
  final MetricKey key;
  final AggregateMetric aggregate;

  // Raw snapshot fields, used by matches(SpanSnapshot). Stored as captured at insert time;
  // the canonical MetricKey above holds the UTF8BytesString-encoded forms.
  private final CharSequence resourceName;
  private final String serviceName;
  private final CharSequence operationName;
  private final CharSequence serviceNameSource;
  private final CharSequence spanType;
  private final short httpStatusCode;
  private final boolean synthetic;
  private final boolean traceRoot;
  private final String spanKind;
  private final String[] peerTagPairs;
  private final String httpMethod;
  private final String httpEndpoint;
  private final String grpcStatusCode;

  AggregateEntry(MetricKey key, SpanSnapshot s, AggregateMetric aggregate) {
    super(hashOf(s));
    this.key = key;
    this.aggregate = aggregate;
    this.resourceName = s.resourceName;
    this.serviceName = s.serviceName;
    this.operationName = s.operationName;
    this.serviceNameSource = s.serviceNameSource;
    this.spanType = s.spanType;
    this.httpStatusCode = s.httpStatusCode;
    this.synthetic = s.synthetic;
    this.traceRoot = s.traceRoot;
    this.spanKind = s.spanKind;
    this.peerTagPairs = s.peerTagPairs;
    this.httpMethod = s.httpMethod;
    this.httpEndpoint = s.httpEndpoint;
    this.grpcStatusCode = s.grpcStatusCode;
  }

  boolean matches(SpanSnapshot s) {
    return httpStatusCode == s.httpStatusCode
        && synthetic == s.synthetic
        && traceRoot == s.traceRoot
        && Objects.equals(resourceName, s.resourceName)
        && Objects.equals(serviceName, s.serviceName)
        && Objects.equals(operationName, s.operationName)
        && Objects.equals(serviceNameSource, s.serviceNameSource)
        && Objects.equals(spanType, s.spanType)
        && Objects.equals(spanKind, s.spanKind)
        && Arrays.equals(peerTagPairs, s.peerTagPairs)
        && Objects.equals(httpMethod, s.httpMethod)
        && Objects.equals(httpEndpoint, s.httpEndpoint)
        && Objects.equals(grpcStatusCode, s.grpcStatusCode);
  }

  /**
   * Computes the 64-bit lookup hash for a {@link SpanSnapshot}. Chained per-field calls -- no
   * varargs / Object[] allocation, no autoboxing on primitive overloads. The constructor's
   * super({@code hashOf(s)}) call uses the same function so an entry built from a snapshot hashes
   * to the same bucket the snapshot itself looks up.
   */
  static long hashOf(SpanSnapshot s) {
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
    if (s.peerTagPairs != null) {
      for (String p : s.peerTagPairs) {
        h = LongHashingUtils.addToHash(h, p);
      }
    }
    h = LongHashingUtils.addToHash(h, s.httpMethod);
    h = LongHashingUtils.addToHash(h, s.httpEndpoint);
    h = LongHashingUtils.addToHash(h, s.grpcStatusCode);
    return h;
  }
}
