package datadog.trace.common.metrics;

import static datadog.trace.api.Functions.UTF8_ENCODE;
import static datadog.trace.bootstrap.instrumentation.api.UTF8BytesString.EMPTY;

import datadog.trace.api.Pair;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.util.Hashtable;
import datadog.trace.util.LongHashingUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * Hashtable entry for the consumer-side aggregator. Holds the UTF8-encoded label fields (the data
 * {@link SerializingMetricWriter} writes to the wire) plus the mutable {@link AggregateMetric}.
 *
 * <p>{@link #matches(SpanSnapshot)} compares the entry's stored UTF8 forms against the snapshot's
 * raw {@code CharSequence}/{@code String}/{@code String[]} fields via content-equality, so {@code
 * String} vs {@code UTF8BytesString} mixing on the same logical key collapses into one entry
 * instead of splitting.
 *
 * <p>The static UTF8 caches that used to live on {@code MetricKey} and {@code
 * ConflatingMetricsAggregator} are consolidated here.
 */
final class AggregateEntry extends Hashtable.Entry {

  // UTF8 caches consolidated from the previous MetricKey + ConflatingMetricsAggregator split.
  private static final DDCache<String, UTF8BytesString> RESOURCE_CACHE =
      DDCaches.newFixedSizeCache(32);
  private static final DDCache<String, UTF8BytesString> SERVICE_CACHE =
      DDCaches.newFixedSizeCache(32);
  private static final DDCache<String, UTF8BytesString> OPERATION_CACHE =
      DDCaches.newFixedSizeCache(64);
  private static final DDCache<String, UTF8BytesString> SERVICE_SOURCE_CACHE =
      DDCaches.newFixedSizeCache(16);
  private static final DDCache<String, UTF8BytesString> TYPE_CACHE = DDCaches.newFixedSizeCache(8);
  private static final DDCache<String, UTF8BytesString> SPAN_KIND_CACHE =
      DDCaches.newFixedSizeCache(16);
  private static final DDCache<String, UTF8BytesString> HTTP_METHOD_CACHE =
      DDCaches.newFixedSizeCache(8);
  private static final DDCache<String, UTF8BytesString> HTTP_ENDPOINT_CACHE =
      DDCaches.newFixedSizeCache(32);
  private static final DDCache<String, UTF8BytesString> GRPC_STATUS_CODE_CACHE =
      DDCaches.newFixedSizeCache(32);

  /**
   * Outer cache keyed by peer-tag name, with an inner per-name cache keyed by value. The inner
   * cache produces the "name:value" encoded form the serializer writes.
   */
  private static final DDCache<
          String, Pair<DDCache<String, UTF8BytesString>, Function<String, UTF8BytesString>>>
      PEER_TAGS_CACHE = DDCaches.newFixedSizeCache(64);

  private static final Function<
          String, Pair<DDCache<String, UTF8BytesString>, Function<String, UTF8BytesString>>>
      PEER_TAGS_CACHE_ADDER =
          key ->
              Pair.of(
                  DDCaches.newFixedSizeCache(512),
                  value -> UTF8BytesString.create(key + ":" + value));

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

  /** Hot-path constructor for the producer/consumer flow. Builds UTF8 fields via the caches. */
  private AggregateEntry(SpanSnapshot s, long keyHash, AggregateMetric aggregate) {
    super(keyHash);
    this.resource = canonicalize(RESOURCE_CACHE, s.resourceName);
    this.service = SERVICE_CACHE.computeIfAbsent(s.serviceName, UTF8_ENCODE);
    this.operationName = canonicalize(OPERATION_CACHE, s.operationName);
    this.serviceSource =
        s.serviceNameSource == null
            ? null
            : canonicalize(SERVICE_SOURCE_CACHE, s.serviceNameSource);
    this.type = canonicalize(TYPE_CACHE, s.spanType);
    this.spanKind = SPAN_KIND_CACHE.computeIfAbsent(s.spanKind, UTF8BytesString::create);
    this.httpMethod =
        s.httpMethod == null
            ? null
            : HTTP_METHOD_CACHE.computeIfAbsent(s.httpMethod, UTF8BytesString::create);
    this.httpEndpoint =
        s.httpEndpoint == null
            ? null
            : HTTP_ENDPOINT_CACHE.computeIfAbsent(s.httpEndpoint, UTF8BytesString::create);
    this.grpcStatusCode =
        s.grpcStatusCode == null
            ? null
            : GRPC_STATUS_CODE_CACHE.computeIfAbsent(s.grpcStatusCode, UTF8BytesString::create);
    this.httpStatusCode = s.httpStatusCode;
    this.synthetic = s.synthetic;
    this.traceRoot = s.traceRoot;
    this.peerTagPairsRaw = s.peerTagPairs;
    this.peerTags = materializePeerTags(s.peerTagPairs);
    this.aggregate = aggregate;
  }

  /** Test-friendly factory mirroring the prior {@code new MetricKey(...)} positional args. */
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
    SpanSnapshot synthetic_snapshot =
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
        synthetic_snapshot, hashOf(synthetic_snapshot), new AggregateMetric());
  }

  /** Construct from a snapshot at consumer-thread miss time. */
  static AggregateEntry forSnapshot(SpanSnapshot s, AggregateMetric aggregate) {
    return new AggregateEntry(s, hashOf(s), aggregate);
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
   * varargs / Object[] allocation, no autoboxing on primitive overloads. The constructor's
   * super({@code hashOf(s)}) call uses the same function so an entry built from a snapshot hashes
   * to the same bucket the snapshot itself looks up.
   *
   * <p>Hashes are content-stable across {@code String} / {@code UTF8BytesString}: {@link
   * UTF8BytesString#hashCode()} returns the underlying {@code String}'s hash.
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
        && java.util.Objects.equals(resource, that.resource)
        && java.util.Objects.equals(service, that.service)
        && java.util.Objects.equals(operationName, that.operationName)
        && java.util.Objects.equals(serviceSource, that.serviceSource)
        && java.util.Objects.equals(type, that.type)
        && java.util.Objects.equals(spanKind, that.spanKind)
        && peerTags.equals(that.peerTags)
        && java.util.Objects.equals(httpMethod, that.httpMethod)
        && java.util.Objects.equals(httpEndpoint, that.httpEndpoint)
        && java.util.Objects.equals(grpcStatusCode, that.grpcStatusCode);
  }

  @Override
  public int hashCode() {
    return (int) keyHash;
  }

  // ----- helpers -----

  private static UTF8BytesString canonicalize(
      DDCache<String, UTF8BytesString> cache, CharSequence charSeq) {
    if (charSeq == null) {
      return EMPTY;
    }
    if (charSeq instanceof UTF8BytesString) {
      return (UTF8BytesString) charSeq;
    }
    return cache.computeIfAbsent(charSeq.toString(), UTF8BytesString::create);
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

  private static List<UTF8BytesString> materializePeerTags(String[] pairs) {
    if (pairs == null || pairs.length == 0) {
      return Collections.emptyList();
    }
    if (pairs.length == 2) {
      return Collections.singletonList(encodePeerTag(pairs[0], pairs[1]));
    }
    List<UTF8BytesString> tags = new ArrayList<>(pairs.length / 2);
    for (int i = 0; i < pairs.length; i += 2) {
      tags.add(encodePeerTag(pairs[i], pairs[i + 1]));
    }
    return tags;
  }

  private static UTF8BytesString encodePeerTag(String name, String value) {
    final Pair<DDCache<String, UTF8BytesString>, Function<String, UTF8BytesString>>
        cacheAndCreator = PEER_TAGS_CACHE.computeIfAbsent(name, PEER_TAGS_CACHE_ADDER);
    return cacheAndCreator.getLeft().computeIfAbsent(value, cacheAndCreator.getRight());
  }

  /**
   * Inverse of {@link #materializePeerTags}: takes pre-encoded UTF8 peer tags and recovers the raw
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
