package datadog.trace.common.metrics;

import datadog.metrics.api.Histogram;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.util.Hashtable;
import datadog.trace.util.LongHashingUtils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Hashtable entry for the consumer-side aggregator. Holds the UTF8-encoded label fields (the data
 * {@link SerializingMetricWriter} writes to the wire) plus the mutable counter / histogram state
 * for the key.
 *
 * <p>UTF8 canonicalization runs through per-field {@link PropertyCardinalityHandler}s (and {@link
 * TagCardinalityHandler}s for peer tags), so cardinality is capped per reporting interval. The
 * critical property: hashing and matching happen <b>after</b> canonicalization, so when a field's
 * cardinality budget is exhausted and overflow values collapse to a {@code blocked_by_tracer}
 * sentinel, those values land in the same bucket and merge into a single entry rather than
 * fragmenting.
 *
 * <p>The aggregator thread is the sole writer. {@link AggregateTable} holds a reusable {@link
 * Canonical} scratch buffer so the canonicalization itself doesn't allocate per lookup; on a miss
 * the buffer's references are copied into a fresh entry. On a hit nothing is allocated.
 *
 * <p>The handlers are reset on the aggregator thread every reporting cycle via {@link
 * #resetCardinalityHandlers()}.
 *
 * <p><b>EMPTY-as-absent contract:</b> all UTF8 fields are non-null. The optional fields ({@code
 * serviceSource}, {@code httpMethod}, {@code httpEndpoint}, {@code grpcStatusCode}) carry {@link
 * UTF8BytesString#EMPTY} when the snapshot had no value; {@link SerializingMetricWriter} tests
 * against {@code EMPTY} (identity comparison on the singleton) to decide whether to emit each field
 * on the wire.
 *
 * <p><b>Thread-safety:</b> not thread-safe. Counter and histogram updates, cardinality-handler
 * registration, and {@link Canonical} use all run on the aggregator thread. Producer threads tag
 * durations via {@link #ERROR_TAG} / {@link #TOP_LEVEL_TAG} bits and hand them off through the
 * snapshot inbox. Test code uses {@link #of} which constructs entries without touching the
 * cardinality handlers.
 */
@SuppressFBWarnings(
    value = {"AT_NONATOMIC_OPERATIONS_ON_SHARED_VARIABLE", "AT_STALE_THREAD_WRITE_OF_PRIMITIVE"},
    justification = "Explicitly not thread-safe. Accumulates counts and durations.")
final class AggregateEntry extends Hashtable.Entry {

  public static final long ERROR_TAG = 0x8000000000000000L;
  public static final long TOP_LEVEL_TAG = 0x4000000000000000L;

  /** Shared empty array used by entries with no peer tags. */
  private static final UTF8BytesString[] EMPTY_PEER_TAGS = new UTF8BytesString[0];

  /** Shared empty array for entries with no additional-tags schema configured. */
  private static final UTF8BytesString[] EMPTY_ADDITIONAL_TAGS = new UTF8BytesString[0];

  // Per-field cardinality handlers. Limits live on MetricCardinalityLimits -- see that class for
  // per-field rationale.
  static final PropertyCardinalityHandler RESOURCE_HANDLER =
      new PropertyCardinalityHandler(MetricCardinalityLimits.RESOURCE);
  static final PropertyCardinalityHandler SERVICE_HANDLER =
      new PropertyCardinalityHandler(MetricCardinalityLimits.SERVICE);
  static final PropertyCardinalityHandler OPERATION_HANDLER =
      new PropertyCardinalityHandler(MetricCardinalityLimits.OPERATION);
  static final PropertyCardinalityHandler SERVICE_SOURCE_HANDLER =
      new PropertyCardinalityHandler(MetricCardinalityLimits.SERVICE_SOURCE);
  static final PropertyCardinalityHandler TYPE_HANDLER =
      new PropertyCardinalityHandler(MetricCardinalityLimits.TYPE);
  static final PropertyCardinalityHandler SPAN_KIND_HANDLER =
      new PropertyCardinalityHandler(MetricCardinalityLimits.SPAN_KIND);
  static final PropertyCardinalityHandler HTTP_METHOD_HANDLER =
      new PropertyCardinalityHandler(MetricCardinalityLimits.HTTP_METHOD);
  static final PropertyCardinalityHandler HTTP_ENDPOINT_HANDLER =
      new PropertyCardinalityHandler(MetricCardinalityLimits.HTTP_ENDPOINT);
  static final PropertyCardinalityHandler GRPC_STATUS_CODE_HANDLER =
      new PropertyCardinalityHandler(MetricCardinalityLimits.GRPC_STATUS_CODE);

  final UTF8BytesString resource;
  final UTF8BytesString service;
  final UTF8BytesString operationName;
  // Optional fields use UTF8BytesString.EMPTY as the "absent" sentinel rather than null. The
  // cardinality handlers map null inputs to EMPTY, and createUtf8 does the same for the of(...)
  // factory, so callers don't need to special-case absence.
  final UTF8BytesString serviceSource;
  final UTF8BytesString type;
  final UTF8BytesString spanKind;
  final UTF8BytesString httpMethod;
  final UTF8BytesString httpEndpoint;
  final UTF8BytesString grpcStatusCode;
  final short httpStatusCode;
  final boolean synthetic;
  final boolean traceRoot;
  final UTF8BytesString[] peerTags;

  /**
   * Per-configured-key additional metric tag values, in {@code AdditionalTagsSchema} order. {@code
   * null} slot = the span didn't set that tag; non-null slots hold the canonical {@code
   * "key:value"} UTF8BytesString (or the schema's blocked sentinel if the value was length-capped
   * or cardinality-capped). Array length matches the configured schema; empty array for the no-
   * additional-tags case.
   */
  final UTF8BytesString[] additionalTags;

  // Mutable aggregate state -- single-thread (aggregator) writer.
  private final Histogram okLatencies = Histogram.newHistogram();

  /**
   * Lazily allocated on the first recorded error. Most entries never see an error and keep this
   * field {@code null} forever; {@link #getErrorLatencies()} returns a shared empty histogram in
   * that case. Once allocated, {@link #clear()} just clears it (does not null) since an entry that
   * errored once tends to error again.
   */
  private Histogram errorLatencies;

  private int errorCount;
  private int hitCount;
  private int topLevelCount;
  private long duration;

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
      UTF8BytesString[] peerTags,
      UTF8BytesString[] additionalTags) {
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
    this.peerTags = peerTags;
    this.additionalTags = additionalTags;
  }

  AggregateEntry recordDurations(int count, AtomicLongArray durations) {
    this.hitCount += count;
    for (int i = 0; i < count && i < durations.length(); ++i) {
      long duration = durations.getAndSet(i, 0);
      if ((duration & TOP_LEVEL_TAG) == TOP_LEVEL_TAG) {
        duration ^= TOP_LEVEL_TAG;
        ++topLevelCount;
      }
      if ((duration & ERROR_TAG) == ERROR_TAG) {
        duration ^= ERROR_TAG;
        errorLatenciesForWrite().accept(duration);
        ++errorCount;
      } else {
        okLatencies.accept(duration);
      }
      this.duration += duration;
    }
    return this;
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
      errorLatenciesForWrite().accept(tagAndDuration);
      ++errorCount;
    } else {
      okLatencies.accept(tagAndDuration);
    }
    duration += tagAndDuration;
    return this;
  }

  /** Lazy-initializes {@link #errorLatencies} on the first error write. */
  private Histogram errorLatenciesForWrite() {
    Histogram h = this.errorLatencies;
    if (h == null) {
      h = Histogram.newHistogram();
      this.errorLatencies = h;
    }
    return h;
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

  /**
   * Returns the error histogram if any error was recorded, or {@code null} otherwise. Callers (only
   * {@link SerializingMetricWriter}) treat null as "no errors this cycle" -- it serializes an empty
   * histogram in that case.
   */
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
    if (this.errorLatencies != null) {
      this.errorLatencies.clear();
    }
  }

  /**
   * Test-friendly factory mirroring the prior {@code new MetricKey(...)} positional args. Bypasses
   * the cardinality handlers so tests don't pollute their state -- {@link UTF8BytesString}s are
   * created directly. Content-equal entries from {@link Canonical#toEntry} still {@link #equals} an
   * entry built via {@code of(...)}.
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
    UTF8BytesString resourceUtf = createUtf8(resource);
    UTF8BytesString serviceUtf = createUtf8(service);
    UTF8BytesString operationNameUtf = createUtf8(operationName);
    UTF8BytesString serviceSourceUtf = createUtf8(serviceSource);
    UTF8BytesString typeUtf = createUtf8(type);
    UTF8BytesString spanKindUtf = createUtf8(spanKind);
    UTF8BytesString httpMethodUtf = createUtf8(httpMethod);
    UTF8BytesString httpEndpointUtf = createUtf8(httpEndpoint);
    UTF8BytesString grpcUtf = createUtf8(grpcStatusCode);
    UTF8BytesString[] peerTagsArray =
        peerTags == null || peerTags.isEmpty()
            ? EMPTY_PEER_TAGS
            : peerTags.toArray(new UTF8BytesString[0]);
    long keyHash =
        hashOf(
            resourceUtf,
            serviceUtf,
            operationNameUtf,
            serviceSourceUtf,
            typeUtf,
            spanKindUtf,
            httpMethodUtf,
            httpEndpointUtf,
            grpcUtf,
            (short) httpStatusCode,
            synthetic,
            traceRoot,
            peerTagsArray,
            peerTagsArray.length,
            EMPTY_ADDITIONAL_TAGS,
            0);
    return new AggregateEntry(
        keyHash,
        resourceUtf,
        serviceUtf,
        operationNameUtf,
        serviceSourceUtf,
        typeUtf,
        spanKindUtf,
        httpMethodUtf,
        httpEndpointUtf,
        grpcUtf,
        (short) httpStatusCode,
        synthetic,
        traceRoot,
        peerTagsArray,
        EMPTY_ADDITIONAL_TAGS);
  }

  /**
   * Resets every cardinality handler's working set. Must be called on the aggregator thread.
   * Existing entries continue to hold their previously-issued {@link UTF8BytesString} references;
   * matches via content-equality so snapshots delivered after a reset still resolve to the existing
   * entries.
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
    PeerTagSchema.INTERNAL.resetCardinalityHandlers();
  }

  /**
   * 64-bit lookup hash, computed over UTF8-encoded fields so that cardinality-blocked values (which
   * all canonicalize to the same sentinel {@link UTF8BytesString}) collide in the same bucket.
   * {@link UTF8BytesString#hashCode()} returns the underlying String hash, so entries built via
   * {@link #of} produce the same hash as entries built from a snapshot with matching content.
   *
   * <p>{@code peerTags} is taken as a {@code (array, length)} pair so the same routine works for
   * the {@link Canonical} scratch buffer (where {@code length < array.length}) and the entry's
   * fixed-size array.
   */
  static long hashOf(
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
      UTF8BytesString[] peerTags,
      int peerTagsLen,
      UTF8BytesString[] additionalTags,
      int additionalTagsLen) {
    long h = 0;
    h = LongHashingUtils.addToHash(h, resource);
    h = LongHashingUtils.addToHash(h, service);
    h = LongHashingUtils.addToHash(h, operationName);
    h = LongHashingUtils.addToHash(h, serviceSource);
    h = LongHashingUtils.addToHash(h, type);
    h = LongHashingUtils.addToHash(h, httpStatusCode);
    h = LongHashingUtils.addToHash(h, synthetic);
    h = LongHashingUtils.addToHash(h, traceRoot);
    h = LongHashingUtils.addToHash(h, spanKind);
    for (int i = 0; i < peerTagsLen; i++) {
      h = LongHashingUtils.addToHash(h, peerTags[i]);
    }
    // Additional tags hash in schema order (which is alphabetical by key, per the schema's
    // construction). null slots are mixed in too so absent-vs-present yields different hashes.
    for (int i = 0; i < additionalTagsLen; i++) {
      h = LongHashingUtils.addToHash(h, additionalTags[i]);
    }
    h = LongHashingUtils.addToHash(h, httpMethod);
    h = LongHashingUtils.addToHash(h, httpEndpoint);
    h = LongHashingUtils.addToHash(h, grpcStatusCode);
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

  UTF8BytesString[] getPeerTags() {
    return peerTags;
  }

  /**
   * Returns the configured-additional-tag values in schema (alphabetical-by-key) order. Each slot
   * is either {@code null} (span didn't set that tag) or the canonical {@code "key:value"}
   * UTF8BytesString. The array's length matches the schema; empty array when no additional tags
   * are configured.
   */
  UTF8BytesString[] getAdditionalTags() {
    return additionalTags;
  }

  /**
   * Equality on the 13 label fields (not on the aggregate). Used only by test mock matchers; the
   * {@link Hashtable} does its own bucketing via {@link #keyHash} + {@link Canonical#matches} and
   * never calls {@code equals}.
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
        && Arrays.equals(peerTags, that.peerTags)
        && Arrays.equals(additionalTags, that.additionalTags)
        && Objects.equals(httpMethod, that.httpMethod)
        && Objects.equals(httpEndpoint, that.httpEndpoint)
        && Objects.equals(grpcStatusCode, that.grpcStatusCode);
  }

  @Override
  public int hashCode() {
    return (int) keyHash;
  }

  /**
   * Reusable scratch buffer for canonicalizing a {@link SpanSnapshot} into UTF8 fields, computing
   * its lookup hash, comparing against existing entries, and building a fresh entry on miss.
   *
   * <p>One instance is held by an {@link AggregateTable} and reused on every {@code findOrInsert}
   * call. Single-threaded use only. Fields are deliberately mutable -- this is a hot-path scratch
   * area, not a value class.
   */
  static final class Canonical {
    UTF8BytesString resource;
    UTF8BytesString service;
    UTF8BytesString operationName;
    UTF8BytesString serviceSource;
    UTF8BytesString type;
    UTF8BytesString spanKind;
    UTF8BytesString httpMethod;
    UTF8BytesString httpEndpoint;
    UTF8BytesString grpcStatusCode;
    short httpStatusCode;
    boolean synthetic;
    boolean traceRoot;

    /**
     * Reusable buffer of canonicalized peer-tag UTF8 forms. Slots {@code [0..peerTagsCount)} are
     * the live entries; the rest is dead space. The buffer doubles when it runs out of room (rare,
     * since typical peer-tag schemas have very few tags). On miss, {@link #toEntry} snapshots into
     * a tight {@link UTF8BytesString}{@code []} for the entry to own. Zero allocation on the hit
     * path.
     */
    UTF8BytesString[] peerTagsBuffer = new UTF8BytesString[4];

    int peerTagsCount;

    /**
     * Reusable buffer of canonicalized additional-tag values. Slots {@code [0..additionalTagsCount)}
     * are the live entries (slot {@code i} = the canonical {@code "key:value"} UTF8BytesString for
     * the snapshot schema's {@code name(i)}, the handler's blocked sentinel if length-capped or
     * cardinality-capped, or {@code null} when the span didn't set that tag). The buffer grows to
     * fit the largest schema we've ever seen and never shrinks; trailing slots beyond {@code
     * additionalTagsCount} are stale dead space and never read. {@link #toEntry} snapshots
     * {@code [0..additionalTagsCount)} into a tight array on miss.
     */
    UTF8BytesString[] additionalTagsBuffer = new UTF8BytesString[0];

    int additionalTagsCount;

    long keyHash;

    Canonical() {}

    /** Canonicalize all fields from {@code s} through the handlers into this buffer. */
    void populate(SpanSnapshot s) {
      this.resource = RESOURCE_HANDLER.register(s.resourceName);
      this.service = SERVICE_HANDLER.register(s.serviceName);
      this.operationName = OPERATION_HANDLER.register(s.operationName);
      this.serviceSource = SERVICE_SOURCE_HANDLER.register(s.serviceNameSource);
      this.type = TYPE_HANDLER.register(s.spanType);
      this.spanKind = SPAN_KIND_HANDLER.register(s.spanKind);
      this.httpMethod = HTTP_METHOD_HANDLER.register(s.httpMethod);
      this.httpEndpoint = HTTP_ENDPOINT_HANDLER.register(s.httpEndpoint);
      this.grpcStatusCode = GRPC_STATUS_CODE_HANDLER.register(s.grpcStatusCode);
      this.httpStatusCode = s.httpStatusCode;
      this.synthetic = s.synthetic;
      this.traceRoot = s.traceRoot;
      populatePeerTags(s.peerTagSchema, s.peerTagValues);
      populateAdditionalTags(s.additionalTagsSchema, s.additionalTagValues);
      this.keyHash = computeKeyHash();
    }

    private long computeKeyHash() {
      return hashOf(
          resource,
          service,
          operationName,
          serviceSource,
          type,
          spanKind,
          httpMethod,
          httpEndpoint,
          grpcStatusCode,
          httpStatusCode,
          synthetic,
          traceRoot,
          peerTagsBuffer,
          peerTagsCount,
          additionalTagsBuffer,
          additionalTagsCount);
    }

    /**
     * Fills {@link #peerTagsBuffer} with canonical UTF8 forms, applying the schema's per-tag
     * handler + warn-once notification at the same index. Returns {@code EMPTY} for null inputs;
     * we elide those from the buffer so the wire-format list-of-pairs only contains present peer
     * tags.
     */
    private void populatePeerTags(PeerTagSchema schema, String[] values) {
      peerTagsCount = 0;
      if (schema == null || values == null) {
        return;
      }
      int n = schema.size();
      for (int i = 0; i < n; i++) {
        UTF8BytesString utf8 = schema.register(i, values[i]);
        if (utf8 != UTF8BytesString.EMPTY) {
          if (peerTagsCount == peerTagsBuffer.length) {
            peerTagsBuffer = Arrays.copyOf(peerTagsBuffer, peerTagsBuffer.length * 2);
          }
          peerTagsBuffer[peerTagsCount++] = utf8;
        }
      }
    }

    /**
     * Fills {@link #additionalTagsBuffer}{@code [0..additionalTagsCount)} with canonical {@code
     * "key:value"} UTF8BytesStrings for each non-null slot in {@code values}, using the snapshot's
     * schema for both the live length and the per-key handler. The handler enforces the length cap
     * and the cardinality cap and returns its {@code "<key>:blocked_by_tracer"} sentinel for
     * either kind of overflow. Absent slots stay {@code null} so the wire format skips them.
     *
     * <p>The buffer grows to fit the snapshot's schema and is never shrunk; trailing slots beyond
     * {@code additionalTagsCount} are never read by hash / match / clone, so they don't leak.
     */
    private void populateAdditionalTags(AdditionalTagsSchema schema, String[] values) {
      additionalTagsCount = schema == null ? 0 : schema.size();
      if (additionalTagsCount == 0 || values == null) {
        additionalTagsCount = 0;
        return;
      }
      if (additionalTagsBuffer.length < additionalTagsCount) {
        additionalTagsBuffer = new UTF8BytesString[additionalTagsCount];
      }
      for (int i = 0; i < additionalTagsCount; i++) {
        String v = values[i];
        additionalTagsBuffer[i] = v == null ? null : schema.register(i, v);
      }
    }

    /**
     * Whether this canonicalized snapshot matches the given entry. Compares UTF8 fields via
     * content-equality (so an entry surviving a handler reset still matches a freshly-canonicalized
     * snapshot of the same content).
     */
    boolean matches(AggregateEntry e) {
      return httpStatusCode == e.httpStatusCode
          && synthetic == e.synthetic
          && traceRoot == e.traceRoot
          && Objects.equals(resource, e.resource)
          && Objects.equals(service, e.service)
          && Objects.equals(operationName, e.operationName)
          && Objects.equals(serviceSource, e.serviceSource)
          && Objects.equals(type, e.type)
          && Objects.equals(spanKind, e.spanKind)
          && tagBufferEquals(peerTagsBuffer, peerTagsCount, e.peerTags)
          && tagBufferEquals(additionalTagsBuffer, additionalTagsCount, e.additionalTags)
          && Objects.equals(httpMethod, e.httpMethod)
          && Objects.equals(httpEndpoint, e.httpEndpoint)
          && Objects.equals(grpcStatusCode, e.grpcStatusCode);
    }

    /**
     * Length-aware indexed comparison so a scratch buffer (whose physical length may exceed its
     * live prefix) can be compared against an entry's tight array. {@code Objects.equals} handles
     * null slots (used by additional tags for "key not set" markers).
     */
    private static boolean tagBufferEquals(UTF8BytesString[] a, int aLen, UTF8BytesString[] b) {
      if (aLen != b.length) {
        return false;
      }
      for (int i = 0; i < aLen; i++) {
        if (!Objects.equals(a[i], b[i])) {
          return false;
        }
      }
      return true;
    }

    /**
     * Build a new entry from the currently-populated canonical fields. The peer-tag and
     * additional-tag buffers are snapshotted into tight {@link UTF8BytesString}{@code []}s so the
     * entry's references stay stable across subsequent {@link #populate} calls.
     */
    AggregateEntry toEntry() {
      UTF8BytesString[] snapshottedPeerTags;
      if (peerTagsCount == 0) {
        snapshottedPeerTags = EMPTY_PEER_TAGS;
      } else {
        snapshottedPeerTags = new UTF8BytesString[peerTagsCount];
        System.arraycopy(peerTagsBuffer, 0, snapshottedPeerTags, 0, peerTagsCount);
      }
      UTF8BytesString[] snapshottedAdditionalTags;
      if (additionalTagsCount == 0) {
        snapshottedAdditionalTags = EMPTY_ADDITIONAL_TAGS;
      } else {
        snapshottedAdditionalTags = new UTF8BytesString[additionalTagsCount];
        System.arraycopy(
            additionalTagsBuffer, 0, snapshottedAdditionalTags, 0, additionalTagsCount);
      }
      return new AggregateEntry(
          keyHash,
          resource,
          service,
          operationName,
          serviceSource,
          type,
          spanKind,
          httpMethod,
          httpEndpoint,
          grpcStatusCode,
          httpStatusCode,
          synthetic,
          traceRoot,
          snapshottedPeerTags,
          snapshottedAdditionalTags);
    }
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
}
