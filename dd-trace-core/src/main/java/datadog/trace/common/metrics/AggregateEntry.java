package datadog.trace.common.metrics;

import static datadog.trace.api.Functions.UTF8_ENCODE;
import static datadog.trace.bootstrap.instrumentation.api.UTF8BytesString.EMPTY;

import datadog.metrics.api.Histogram;
import datadog.trace.api.Pair;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.util.Hashtable;
import datadog.trace.util.LongHashingUtils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * Hashtable entry for the consumer-side aggregator. Holds the UTF8-encoded label fields that {@link
 * SerializingMetricWriter} writes to the wire plus the mutable counter/histogram state for the key.
 *
 * <p>{@link #matches(SpanSnapshot)} compares the entry's stored UTF8 forms against the snapshot's
 * raw {@code CharSequence}/{@code String}/{@code String[]} fields via content-equality, so {@code
 * String} vs {@code UTF8BytesString} mixing on the same logical key collapses into one entry
 * instead of splitting.
 *
 * <p>The static UTF8 caches that used to live on {@code MetricKey} and {@code
 * ConflatingMetricsAggregator} are consolidated here.
 *
 * <p><b>Not thread-safe.</b> Counter and histogram updates are performed by the single aggregator
 * thread; producer threads tag durations via {@link #ERROR_TAG} / {@link #TOP_LEVEL_TAG} bits and
 * hand them off through the snapshot inbox.
 */
@SuppressFBWarnings(
    value = {"AT_NONATOMIC_OPERATIONS_ON_SHARED_VARIABLE", "AT_STALE_THREAD_WRITE_OF_PRIMITIVE"},
    justification = "Explicitly not thread-safe. Accumulates counts and durations.")
final class AggregateEntry extends Hashtable.Entry {

  public static final long ERROR_TAG = 0x8000000000000000L;
  public static final long TOP_LEVEL_TAG = 0x4000000000000000L;

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
  @Nullable private final UTF8BytesString serviceSource;
  private final UTF8BytesString type;
  private final UTF8BytesString spanKind;
  @Nullable private final UTF8BytesString httpMethod;
  @Nullable private final UTF8BytesString httpEndpoint;
  @Nullable private final UTF8BytesString grpcStatusCode;
  private final short httpStatusCode;
  private final boolean synthetic;
  private final boolean traceRoot;

  // Peer tags carried in two forms: parallel String[] arrays mirroring the snapshot's (schema +
  // values) shape for matches(), and pre-encoded List<UTF8BytesString> ("name:value") for the
  // serializer. peerTagNames is the schema's names array (shared by-reference when the schema
  // hasn't been replaced); peerTagValues is the per-span String[] parallel to it.
  @Nullable private final String[] peerTagNames;
  @Nullable private final String[] peerTagValues;
  private final List<UTF8BytesString> peerTags;

  // Mutable aggregate state -- single-thread (consumer/aggregator) writer.
  private final Histogram okLatencies = Histogram.newHistogram();
  private final Histogram errorLatencies = Histogram.newHistogram();
  private int errorCount;
  private int hitCount;
  private int topLevelCount;
  private long duration;

  /** Hot-path constructor for the producer/consumer flow. Builds UTF8 fields via the caches. */
  private AggregateEntry(SpanSnapshot s, long keyHash) {
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
    this.peerTagNames = s.peerTagSchema == null ? null : s.peerTagSchema.names;
    this.peerTagValues = s.peerTagValues;
    this.peerTags = materializePeerTags(this.peerTagNames, this.peerTagValues);
  }

  /**
   * Test-friendly factory mirroring the prior {@code new MetricKey(...)} positional args. Accepts a
   * pre-encoded {@code List<UTF8BytesString>} of {@code "name:value"} peer tags and recovers the
   * parallel-array {@code (names, values)} form by splitting on the {@code ':'} delimiter.
   *
   * <p><b>Test-only.</b> The split is at the <em>first</em> {@code ':'}, so peer-tag values
   * containing a colon (URLs, IPv6 addresses, {@code service:env} patterns) will be silently
   * misparsed and the recovered (name, value) pair will be wrong. Keep test data colon-free in
   * peer-tag values, or wire production-style snapshots through {@link #forSnapshot(SpanSnapshot)}
   * instead.
   */
  static AggregateEntry of(
      CharSequence resource,
      CharSequence service,
      CharSequence operationName,
      @Nullable CharSequence serviceSource,
      CharSequence type,
      int httpStatusCode,
      boolean synthetic,
      boolean traceRoot,
      CharSequence spanKind,
      @Nullable List<UTF8BytesString> peerTags,
      @Nullable CharSequence httpMethod,
      @Nullable CharSequence httpEndpoint,
      @Nullable CharSequence grpcStatusCode) {
    PeerTagSchema schema = null;
    String[] values = null;
    if (peerTags != null && !peerTags.isEmpty()) {
      String[] names = new String[peerTags.size()];
      values = new String[peerTags.size()];
      int i = 0;
      for (UTF8BytesString t : peerTags) {
        String s = t.toString();
        int colon = s.indexOf(':');
        names[i] = colon < 0 ? s : s.substring(0, colon);
        values[i] = colon < 0 ? "" : s.substring(colon + 1);
        i++;
      }
      schema = PeerTagSchema.testSchema(names);
    }
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
            schema,
            values,
            httpMethod == null ? null : httpMethod.toString(),
            httpEndpoint == null ? null : httpEndpoint.toString(),
            grpcStatusCode == null ? null : grpcStatusCode.toString(),
            0L);
    return new AggregateEntry(synthetic_snapshot, hashOf(synthetic_snapshot));
  }

  /** Construct from a snapshot at consumer-thread miss time. */
  static AggregateEntry forSnapshot(SpanSnapshot s) {
    return new AggregateEntry(s, hashOf(s));
  }

  /**
   * Records a single hit. {@code tagAndDuration} carries the duration nanos with optional {@link
   * #ERROR_TAG} / {@link #TOP_LEVEL_TAG} bits OR-ed in.
   */
  AggregateEntry recordOneDuration(long tagAndDuration) {
    ++hitCount;
    if ((tagAndDuration & TOP_LEVEL_TAG) == TOP_LEVEL_TAG) {
      tagAndDuration ^= TOP_LEVEL_TAG;
      ++topLevelCount;
    }
    if ((tagAndDuration & ERROR_TAG) == ERROR_TAG) {
      tagAndDuration ^= ERROR_TAG;
      errorLatencies.accept(tagAndDuration);
      ++errorCount;
    } else {
      okLatencies.accept(tagAndDuration);
    }
    duration += tagAndDuration;
    return this;
  }

  int getErrorCount() {
    return errorCount;
  }

  int getHitCount() {
    return hitCount;
  }

  int getTopLevelCount() {
    return topLevelCount;
  }

  long getDuration() {
    return duration;
  }

  Histogram getOkLatencies() {
    return okLatencies;
  }

  Histogram getErrorLatencies() {
    return errorLatencies;
  }

  @SuppressFBWarnings("AT_NONATOMIC_64BIT_PRIMITIVE")
  void clear() {
    this.errorCount = 0;
    this.hitCount = 0;
    this.topLevelCount = 0;
    this.duration = 0;
    this.okLatencies.clear();
    this.errorLatencies.clear();
  }

  boolean matches(SpanSnapshot s) {
    String[] snapshotNames = s.peerTagSchema == null ? null : s.peerTagSchema.names;
    return httpStatusCode == s.httpStatusCode
        && synthetic == s.synthetic
        && traceRoot == s.traceRoot
        && contentEquals(resource, s.resourceName)
        && stringContentEquals(service, s.serviceName)
        && contentEquals(operationName, s.operationName)
        && contentEquals(serviceSource, s.serviceNameSource)
        && contentEquals(type, s.spanType)
        && stringContentEquals(spanKind, s.spanKind)
        && Arrays.equals(peerTagNames, snapshotNames)
        && Arrays.equals(peerTagValues, s.peerTagValues)
        && stringContentEquals(httpMethod, s.httpMethod)
        && stringContentEquals(httpEndpoint, s.httpEndpoint)
        && stringContentEquals(grpcStatusCode, s.grpcStatusCode);
  }

  /**
   * Pre-checks {@link #keyHash} against {@code keyHash} before delegating to {@link
   * #matches(SpanSnapshot)}. The hash check is cheap and rules out most mismatches without touching
   * the field-by-field comparison.
   */
  boolean matches(long keyHash, SpanSnapshot s) {
    return this.keyHash == keyHash && matches(s);
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
    // Always mix in both the schema's content hash and the values' content hash, unconditionally
    // (no null-skip). PeerTagSchema overrides hashCode() to be content-based on names; we use
    // Arrays.hashCode for the String[] values since the default Object[].hashCode is identity-
    // based, not content-based. Null inputs hash to 0 for both, distinct from any real schema's
    // hash or any non-empty values array.
    h = LongHashingUtils.addToHash(h, s.peerTagSchema);
    h = LongHashingUtils.addToHash(h, Arrays.hashCode(s.peerTagValues));
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

  @Nullable
  UTF8BytesString getServiceSource() {
    return serviceSource;
  }

  UTF8BytesString getType() {
    return type;
  }

  UTF8BytesString getSpanKind() {
    return spanKind;
  }

  @Nullable
  UTF8BytesString getHttpMethod() {
    return httpMethod;
  }

  @Nullable
  UTF8BytesString getHttpEndpoint() {
    return httpEndpoint;
  }

  @Nullable
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

  /**
   * UTF8 vs raw CharSequence content-equality, no allocation in the common (String) case.
   *
   * <p>Treats {@code null} and empty (length 0) as equivalent on either side. This matches the
   * canonicalization semantics: {@link #canonicalize} maps a {@code null} input to {@link
   * UTF8BytesString#EMPTY}, so an entry built from a snapshot with a null field needs to match a
   * subsequent snapshot whose field is still null. {@code intHash(null) == 0 == "".hashCode()}, so
   * the hash already agrees with this view.
   */
  private static boolean contentEquals(UTF8BytesString a, CharSequence b) {
    if (a == null) {
      return b == null || b.length() == 0;
    }
    if (b == null) {
      return a.length() == 0;
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
      return b == null || b.isEmpty();
    }
    if (b == null) {
      return a.length() == 0;
    }
    return a.toString().equals(b);
  }

  /**
   * Encodes the per-span peer-tag values into the {@code List<UTF8BytesString>} the serializer
   * consumes. Reads name/value pairs at the same index from the schema's names and the snapshot's
   * values; null value slots are skipped (the span didn't set that peer tag). Counts hits once for
   * exact-size allocation and preserves the singletonList fast path for the common one-entry case
   * (e.g. internal-kind base.service).
   */
  private static List<UTF8BytesString> materializePeerTags(
      @Nullable String[] names, @Nullable String[] values) {
    if (names == null || values == null) {
      return Collections.emptyList();
    }
    int n = names.length;
    int firstHit = -1;
    int hitCount = 0;
    for (int i = 0; i < n; i++) {
      if (values[i] != null) {
        if (hitCount == 0) firstHit = i;
        hitCount++;
      }
    }
    if (hitCount == 0) {
      return Collections.emptyList();
    }
    if (hitCount == 1) {
      return Collections.singletonList(encodePeerTag(names[firstHit], values[firstHit]));
    }
    List<UTF8BytesString> tags = new ArrayList<>(hitCount);
    for (int i = firstHit; i < n; i++) {
      if (values[i] != null) {
        tags.add(encodePeerTag(names[i], values[i]));
      }
    }
    return tags;
  }

  private static UTF8BytesString encodePeerTag(String name, String value) {
    final Pair<DDCache<String, UTF8BytesString>, Function<String, UTF8BytesString>>
        cacheAndCreator = PEER_TAGS_CACHE.computeIfAbsent(name, PEER_TAGS_CACHE_ADDER);
    return cacheAndCreator.getLeft().computeIfAbsent(value, cacheAndCreator.getRight());
  }
}
