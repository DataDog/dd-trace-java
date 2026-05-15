package datadog.trace.common.metrics;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.util.Hashtable;
import datadog.trace.util.LongHashingUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Hashtable entry for the consumer-side aggregator. Holds the UTF8-encoded label fields (the data
 * {@link SerializingMetricWriter} writes to the wire) plus the mutable {@link AggregateMetric}.
 *
 * <p>{@link #matches(SpanSnapshot)} compares the entry's stored UTF8 forms against the snapshot's
 * raw {@code CharSequence}/{@code String}/{@code String[]} fields via content-equality, so {@code
 * String} vs {@code UTF8BytesString} mixing on the same logical key collapses into one entry
 * instead of splitting.
 *
 * <p>UTF8 canonicalization runs through per-field {@link PropertyCardinalityHandler}s (and {@link
 * TagCardinalityHandler}s for peer tags), so cardinality is capped per reporting interval and
 * overflow values are bucketed into a {@code blocked_by_tracer} sentinel rather than allowed to
 * grow without bound. The handlers are reset on the aggregator thread every reporting cycle via
 * {@link #resetCardinalityHandlers()}.
 *
 * <p><b>Thread-safety:</b> the cardinality handlers are not thread-safe. Only the aggregator thread
 * may call {@link #forSnapshot} or {@link #resetCardinalityHandlers}. Test code uses {@link #of}
 * which constructs entries without touching the handlers.
 */
final class AggregateEntry extends Hashtable.Entry {

  // Per-field cardinality limits. Identical to the prior DDCache sizes.
  private static final PropertyCardinalityHandler RESOURCE_HANDLER =
      new PropertyCardinalityHandler(32);
  private static final PropertyCardinalityHandler SERVICE_HANDLER =
      new PropertyCardinalityHandler(32);
  private static final PropertyCardinalityHandler OPERATION_HANDLER =
      new PropertyCardinalityHandler(64);
  private static final PropertyCardinalityHandler SERVICE_SOURCE_HANDLER =
      new PropertyCardinalityHandler(16);
  private static final PropertyCardinalityHandler TYPE_HANDLER = new PropertyCardinalityHandler(8);
  private static final PropertyCardinalityHandler SPAN_KIND_HANDLER =
      new PropertyCardinalityHandler(16);
  private static final PropertyCardinalityHandler HTTP_METHOD_HANDLER =
      new PropertyCardinalityHandler(8);
  private static final PropertyCardinalityHandler HTTP_ENDPOINT_HANDLER =
      new PropertyCardinalityHandler(32);
  private static final PropertyCardinalityHandler GRPC_STATUS_CODE_HANDLER =
      new PropertyCardinalityHandler(32);

  /** Per-peer-tag-name {@link TagCardinalityHandler}, each sized to 512 distinct values. */
  private static final Map<String, TagCardinalityHandler> PEER_TAG_HANDLERS = new HashMap<>();

  private static final int PEER_TAG_VALUE_LIMIT = 512;

  private final UTF8BytesString resource;
  private final UTF8BytesString service;
  private final UTF8BytesString operationName;
  private final UTF8BytesString serviceSource; // nullable
  private final UTF8BytesString type;
  private final UTF8BytesString spanKind;
  private final UTF8BytesString httpMethod; // nullable
  private final UTF8BytesString httpEndpoint; // nullable
  private final UTF8BytesString grpcStatusCode; // nullable
  private final short httpStatusCode;
  private final boolean synthetic;
  private final boolean traceRoot;

  // Peer tags carried in two forms: raw String[] for matches() against the snapshot's pairs,
  // and pre-encoded List<UTF8BytesString> ("name:value") for the serializer.
  private final String[] peerTagPairsRaw;
  private final List<UTF8BytesString> peerTags;

  final AggregateMetric aggregate;

  /** Field-bearing constructor used by both the hot path and the test factory. */
  private AggregateEntry(
      long keyHash,
      UTF8BytesString resource,
      UTF8BytesString service,
      UTF8BytesString operationName,
      UTF8BytesString serviceSource,
      UTF8BytesString type,
      UTF8BytesString spanKind,
      UTF8BytesString httpMethod,
      UTF8BytesString httpEndpoint,
      UTF8BytesString grpcStatusCode,
      short httpStatusCode,
      boolean synthetic,
      boolean traceRoot,
      String[] peerTagPairsRaw,
      List<UTF8BytesString> peerTags,
      AggregateMetric aggregate) {
    super(keyHash);
    this.resource = resource;
    this.service = service;
    this.operationName = operationName;
    this.serviceSource = serviceSource;
    this.type = type;
    this.spanKind = spanKind;
    this.httpMethod = httpMethod;
    this.httpEndpoint = httpEndpoint;
    this.grpcStatusCode = grpcStatusCode;
    this.httpStatusCode = httpStatusCode;
    this.synthetic = synthetic;
    this.traceRoot = traceRoot;
    this.peerTagPairsRaw = peerTagPairsRaw;
    this.peerTags = peerTags;
    this.aggregate = aggregate;
  }

  /**
   * Production hot path: canonicalize each snapshot field via the cardinality handlers. Must be
   * called on the aggregator thread. Null-valued fields short-circuit to {@link
   * UTF8BytesString#EMPTY} (or {@code null} for optional ones) so they don't consume a cardinality
   * slot.
   */
  static AggregateEntry forSnapshot(SpanSnapshot s, AggregateMetric aggregate) {
    return new AggregateEntry(
        hashOf(s),
        registerOrEmpty(RESOURCE_HANDLER, s.resourceName),
        registerOrEmpty(SERVICE_HANDLER, s.serviceName),
        registerOrEmpty(OPERATION_HANDLER, s.operationName),
        s.serviceNameSource == null ? null : SERVICE_SOURCE_HANDLER.register(s.serviceNameSource),
        registerOrEmpty(TYPE_HANDLER, s.spanType),
        registerOrEmpty(SPAN_KIND_HANDLER, s.spanKind),
        s.httpMethod == null ? null : HTTP_METHOD_HANDLER.register(s.httpMethod),
        s.httpEndpoint == null ? null : HTTP_ENDPOINT_HANDLER.register(s.httpEndpoint),
        s.grpcStatusCode == null ? null : GRPC_STATUS_CODE_HANDLER.register(s.grpcStatusCode),
        s.httpStatusCode,
        s.synthetic,
        s.traceRoot,
        s.peerTagPairs,
        canonicalizePeerTags(s.peerTagPairs),
        aggregate);
  }

  private static UTF8BytesString registerOrEmpty(
      PropertyCardinalityHandler handler, CharSequence value) {
    return value == null ? UTF8BytesString.EMPTY : handler.register(value);
  }

  /**
   * Test-friendly factory mirroring the prior {@code new MetricKey(...)} positional args. Bypasses
   * the cardinality handlers so tests don't pollute their state -- {@link UTF8BytesString}s are
   * created directly. Content-equality on the resulting entry still matches an entry built via
   * {@link #forSnapshot} from a snapshot of the same shape.
   */
  static AggregateEntry of(
      CharSequence resource,
      CharSequence service,
      CharSequence operationName,
      CharSequence serviceSource,
      CharSequence type,
      int httpStatusCode,
      boolean synthetic,
      boolean traceRoot,
      CharSequence spanKind,
      List<UTF8BytesString> peerTags,
      CharSequence httpMethod,
      CharSequence httpEndpoint,
      CharSequence grpcStatusCode) {
    String[] rawPairs = peerTagsToRawPairs(peerTags);
    SpanSnapshot syntheticSnapshot =
        new SpanSnapshot(
            resource,
            service == null ? null : service.toString(),
            operationName,
            serviceSource,
            type,
            (short) httpStatusCode,
            synthetic,
            traceRoot,
            spanKind == null ? null : spanKind.toString(),
            rawPairs,
            httpMethod == null ? null : httpMethod.toString(),
            httpEndpoint == null ? null : httpEndpoint.toString(),
            grpcStatusCode == null ? null : grpcStatusCode.toString(),
            0L);
    return new AggregateEntry(
        hashOf(syntheticSnapshot),
        createUtf8(resource),
        createUtf8(service),
        createUtf8(operationName),
        serviceSource == null ? null : createUtf8(serviceSource),
        createUtf8(type),
        createUtf8(spanKind),
        httpMethod == null ? null : createUtf8(httpMethod),
        httpEndpoint == null ? null : createUtf8(httpEndpoint),
        grpcStatusCode == null ? null : createUtf8(grpcStatusCode),
        (short) httpStatusCode,
        synthetic,
        traceRoot,
        rawPairs,
        peerTags == null ? Collections.emptyList() : peerTags,
        new AggregateMetric());
  }

  /**
   * Resets every cardinality handler's working set. Must be called on the aggregator thread.
   * Existing entries continue to hold their previously-issued {@link UTF8BytesString} references;
   * matches() uses content-equality so snapshots delivered after a reset still resolve to the
   * existing entries.
   */
  static void resetCardinalityHandlers() {
    RESOURCE_HANDLER.reset();
    SERVICE_HANDLER.reset();
    OPERATION_HANDLER.reset();
    SERVICE_SOURCE_HANDLER.reset();
    TYPE_HANDLER.reset();
    SPAN_KIND_HANDLER.reset();
    HTTP_METHOD_HANDLER.reset();
    HTTP_ENDPOINT_HANDLER.reset();
    GRPC_STATUS_CODE_HANDLER.reset();
    for (TagCardinalityHandler h : PEER_TAG_HANDLERS.values()) {
      h.reset();
    }
  }

  boolean matches(SpanSnapshot s) {
    return httpStatusCode == s.httpStatusCode
        && synthetic == s.synthetic
        && traceRoot == s.traceRoot
        && contentEquals(resource, s.resourceName)
        && stringContentEquals(service, s.serviceName)
        && contentEquals(operationName, s.operationName)
        && contentEquals(serviceSource, s.serviceNameSource)
        && contentEquals(type, s.spanType)
        && stringContentEquals(spanKind, s.spanKind)
        && Arrays.equals(peerTagPairsRaw, s.peerTagPairs)
        && stringContentEquals(httpMethod, s.httpMethod)
        && stringContentEquals(httpEndpoint, s.httpEndpoint)
        && stringContentEquals(grpcStatusCode, s.grpcStatusCode);
  }

  /**
   * Computes the 64-bit lookup hash for a {@link SpanSnapshot}. Chained per-field calls -- no
   * varargs / Object[] allocation, no autoboxing on primitive overloads. Hashes are content-stable
   * across {@code String} / {@code UTF8BytesString} because {@link UTF8BytesString#hashCode()}
   * returns the underlying {@code String}'s hash.
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

  // Accessors for SerializingMetricWriter.
  UTF8BytesString getResource() {
    return resource;
  }

  UTF8BytesString getService() {
    return service;
  }

  UTF8BytesString getOperationName() {
    return operationName;
  }

  UTF8BytesString getServiceSource() {
    return serviceSource;
  }

  UTF8BytesString getType() {
    return type;
  }

  UTF8BytesString getSpanKind() {
    return spanKind;
  }

  UTF8BytesString getHttpMethod() {
    return httpMethod;
  }

  UTF8BytesString getHttpEndpoint() {
    return httpEndpoint;
  }

  UTF8BytesString getGrpcStatusCode() {
    return grpcStatusCode;
  }

  int getHttpStatusCode() {
    return httpStatusCode;
  }

  boolean isSynthetics() {
    return synthetic;
  }

  boolean isTraceRoot() {
    return traceRoot;
  }

  List<UTF8BytesString> getPeerTags() {
    return peerTags;
  }

  /**
   * Equality on the 13 label fields (not on the aggregate). Used only by test mock matchers; the
   * {@link Hashtable} does its own bucketing via {@link #keyHash} + {@link #matches(SpanSnapshot)}
   * and never calls {@code equals}.
   */
  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof AggregateEntry)) return false;
    AggregateEntry that = (AggregateEntry) o;
    return httpStatusCode == that.httpStatusCode
        && synthetic == that.synthetic
        && traceRoot == that.traceRoot
        && Objects.equals(resource, that.resource)
        && Objects.equals(service, that.service)
        && Objects.equals(operationName, that.operationName)
        && Objects.equals(serviceSource, that.serviceSource)
        && Objects.equals(type, that.type)
        && Objects.equals(spanKind, that.spanKind)
        && peerTags.equals(that.peerTags)
        && Objects.equals(httpMethod, that.httpMethod)
        && Objects.equals(httpEndpoint, that.httpEndpoint)
        && Objects.equals(grpcStatusCode, that.grpcStatusCode);
  }

  @Override
  public int hashCode() {
    return (int) keyHash;
  }

  // ----- helpers -----

  /** Direct {@link UTF8BytesString} creation that bypasses the cardinality handlers. */
  private static UTF8BytesString createUtf8(CharSequence cs) {
    if (cs == null) {
      return UTF8BytesString.EMPTY;
    }
    if (cs instanceof UTF8BytesString) {
      return (UTF8BytesString) cs;
    }
    return UTF8BytesString.create(cs.toString());
  }

  /** UTF8 vs raw CharSequence content-equality, no allocation in the common (String) case. */
  private static boolean contentEquals(UTF8BytesString a, CharSequence b) {
    if (a == null) {
      return b == null;
    }
    if (b == null) {
      return false;
    }
    // UTF8BytesString.toString() returns the underlying String -- O(1), no allocation.
    String aStr = a.toString();
    if (b instanceof String) {
      return aStr.equals(b);
    }
    if (b instanceof UTF8BytesString) {
      return aStr.equals(b.toString());
    }
    return aStr.contentEquals(b);
  }

  private static boolean stringContentEquals(UTF8BytesString a, String b) {
    if (a == null) {
      return b == null;
    }
    return b != null && a.toString().equals(b);
  }

  /** Production-path peer-tag canonicalization via per-name {@link TagCardinalityHandler}. */
  private static List<UTF8BytesString> canonicalizePeerTags(String[] pairs) {
    if (pairs == null || pairs.length == 0) {
      return Collections.emptyList();
    }
    if (pairs.length == 2) {
      return Collections.singletonList(handlerFor(pairs[0]).register(pairs[1]));
    }
    List<UTF8BytesString> tags = new ArrayList<>(pairs.length / 2);
    for (int i = 0; i < pairs.length; i += 2) {
      tags.add(handlerFor(pairs[i]).register(pairs[i + 1]));
    }
    return tags;
  }

  private static TagCardinalityHandler handlerFor(String peerTagName) {
    TagCardinalityHandler h = PEER_TAG_HANDLERS.get(peerTagName);
    if (h != null) {
      return h;
    }
    h = new TagCardinalityHandler(peerTagName, PEER_TAG_VALUE_LIMIT);
    PEER_TAG_HANDLERS.put(peerTagName, h);
    return h;
  }

  /**
   * Inverse of {@link #canonicalizePeerTags}: takes pre-encoded UTF8 peer tags and recovers the raw
   * {@code [name0, value0, name1, value1, ...]} pairs. Used by the test factory {@link #of}, not by
   * the hot path.
   */
  private static String[] peerTagsToRawPairs(List<UTF8BytesString> peerTags) {
    if (peerTags == null || peerTags.isEmpty()) {
      return null;
    }
    String[] pairs = new String[peerTags.size() * 2];
    int i = 0;
    for (UTF8BytesString peerTag : peerTags) {
      String s = peerTag.toString();
      int colon = s.indexOf(':');
      pairs[i++] = colon < 0 ? s : s.substring(0, colon);
      pairs[i++] = colon < 0 ? "" : s.substring(colon + 1);
    }
    return pairs;
  }
}
