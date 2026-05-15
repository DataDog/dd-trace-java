package datadog.trace.common.metrics;

import datadog.metrics.api.Histogram;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.util.Hashtable;
import datadog.trace.util.LongHashingUtils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Hashtable entry for the consumer-side aggregator. Holds the UTF8-encoded label fields (the data
 * {@link SerializingMetricWriter} writes to the wire) plus the mutable per-bucket counters and
 * latency histograms.
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
 * <p><b>Thread-safety:</b> the cardinality handlers and {@link Canonical} are not thread-safe. Only
 * the aggregator thread may call {@link Canonical#populate} or {@link #resetCardinalityHandlers}.
 * Test code uses {@link #of} which constructs entries without touching the handlers.
 */
@SuppressFBWarnings(
    value = {"AT_NONATOMIC_OPERATIONS_ON_SHARED_VARIABLE", "AT_STALE_THREAD_WRITE_OF_PRIMITIVE"},
    justification =
        "Recording counters are mutated only on the aggregator thread; not thread-safe by design.")
final class AggregateEntry extends Hashtable.Entry {

  /** Top bit of the duration word: set when the recorded span was an error. */
  static final long ERROR_TAG = 0x8000000000000000L;

  /** Second-from-top bit: set when the recorded span was a top-level span. */
  static final long TOP_LEVEL_TAG = 0x4000000000000000L;

  // Per-field cardinality limits. Identical to the prior DDCache sizes.
  static final PropertyCardinalityHandler RESOURCE_HANDLER = new PropertyCardinalityHandler(32);
  static final PropertyCardinalityHandler SERVICE_HANDLER = new PropertyCardinalityHandler(32);
  static final PropertyCardinalityHandler OPERATION_HANDLER = new PropertyCardinalityHandler(64);
  static final PropertyCardinalityHandler SERVICE_SOURCE_HANDLER =
      new PropertyCardinalityHandler(16);
  static final PropertyCardinalityHandler TYPE_HANDLER = new PropertyCardinalityHandler(8);
  static final PropertyCardinalityHandler SPAN_KIND_HANDLER = new PropertyCardinalityHandler(16);
  static final PropertyCardinalityHandler HTTP_METHOD_HANDLER = new PropertyCardinalityHandler(8);
  static final PropertyCardinalityHandler HTTP_ENDPOINT_HANDLER =
      new PropertyCardinalityHandler(32);
  static final PropertyCardinalityHandler GRPC_STATUS_CODE_HANDLER =
      new PropertyCardinalityHandler(32);

  final UTF8BytesString resource;
  final UTF8BytesString service;
  final UTF8BytesString operationName;
  final UTF8BytesString serviceSource; // nullable
  final UTF8BytesString type;
  final UTF8BytesString spanKind;
  final UTF8BytesString httpMethod; // nullable
  final UTF8BytesString httpEndpoint; // nullable
  final UTF8BytesString grpcStatusCode; // nullable
  final short httpStatusCode;
  final boolean synthetic;
  final boolean traceRoot;
  final List<UTF8BytesString> peerTags;

  // Recording state. Mutated only on the aggregator thread. Not thread-safe.
  private final Histogram okLatencies;
  private final Histogram errorLatencies;
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
      List<UTF8BytesString> peerTags) {
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
    this.okLatencies = Histogram.newHistogram();
    this.errorLatencies = Histogram.newHistogram();
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
    UTF8BytesString serviceSourceUtf = serviceSource == null ? null : createUtf8(serviceSource);
    UTF8BytesString typeUtf = createUtf8(type);
    UTF8BytesString spanKindUtf = createUtf8(spanKind);
    UTF8BytesString httpMethodUtf = httpMethod == null ? null : createUtf8(httpMethod);
    UTF8BytesString httpEndpointUtf = httpEndpoint == null ? null : createUtf8(httpEndpoint);
    UTF8BytesString grpcUtf = grpcStatusCode == null ? null : createUtf8(grpcStatusCode);
    List<UTF8BytesString> peerTagsList = peerTags == null ? Collections.emptyList() : peerTags;
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
            peerTagsList);
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
        peerTagsList);
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
    PeerTagSchema.resetAll();
  }

  /**
   * 64-bit lookup hash, computed over UTF8-encoded fields so that cardinality-blocked values (which
   * all canonicalize to the same sentinel {@link UTF8BytesString}) collide in the same bucket.
   * {@link UTF8BytesString#hashCode()} returns the underlying String hash, so entries built via
   * {@link #of} produce the same hash as entries built from a snapshot with matching content.
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
      List<UTF8BytesString> peerTags) {
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
    // indexed iteration -- avoids the iterator allocation a for-each over a List would do
    int peerTagCount = peerTags.size();
    for (int i = 0; i < peerTagCount; i++) {
      h = LongHashingUtils.addToHash(h, peerTags.get(i));
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

  List<UTF8BytesString> getPeerTags() {
    return peerTags;
  }

  // ----- recording state accessors -----

  int getHitCount() {
    return hitCount;
  }

  int getErrorCount() {
    return errorCount;
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

  /**
   * Records {@code count} durations from {@code durations} (positions 0..count-1). Used by
   * integration tests; production code uses {@link #recordOneDuration}.
   */
  AggregateEntry recordDurations(int count, AtomicLongArray durations) {
    this.hitCount += count;
    for (int i = 0; i < count && i < durations.length(); ++i) {
      long d = durations.getAndSet(i, 0);
      if ((d & TOP_LEVEL_TAG) == TOP_LEVEL_TAG) {
        d ^= TOP_LEVEL_TAG;
        ++topLevelCount;
      }
      if ((d & ERROR_TAG) == ERROR_TAG) {
        d ^= ERROR_TAG;
        errorLatencies.accept(d);
        ++errorCount;
      } else {
        okLatencies.accept(d);
      }
      this.duration += d;
    }
    return this;
  }

  /** Clears the recording state. Histograms are reused. */
  @SuppressFBWarnings("AT_NONATOMIC_64BIT_PRIMITIVE")
  void clearAggregate() {
    this.errorCount = 0;
    this.hitCount = 0;
    this.topLevelCount = 0;
    this.duration = 0;
    this.okLatencies.clear();
    this.errorLatencies.clear();
  }

  /**
   * Equality on the 13 label fields (not on the recording counters). Used only by test mock
   * matchers; the {@link Hashtable} does its own bucketing via {@link #keyHash} + {@link
   * Canonical#matches} and never calls {@code equals}.
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
    UTF8BytesString serviceSource; // nullable
    UTF8BytesString type;
    UTF8BytesString spanKind;
    UTF8BytesString httpMethod; // nullable
    UTF8BytesString httpEndpoint; // nullable
    UTF8BytesString grpcStatusCode; // nullable
    short httpStatusCode;
    boolean synthetic;
    boolean traceRoot;

    /**
     * Reusable buffer of canonicalized peer-tag UTF8 forms. Cleared and refilled in {@link
     * #populate}; on miss, {@link #toEntry} copies it into an immutable list for the entry to own.
     * Zero allocation on the hit path.
     */
    final ArrayList<UTF8BytesString> peerTagsBuffer = new ArrayList<>(4);

    long keyHash;

    /** Canonicalize all fields from {@code s} through the handlers into this buffer. */
    void populate(SpanSnapshot s) {
      this.resource = registerOrEmpty(RESOURCE_HANDLER, s.resourceName);
      this.service = registerOrEmpty(SERVICE_HANDLER, s.serviceName);
      this.operationName = registerOrEmpty(OPERATION_HANDLER, s.operationName);
      this.serviceSource =
          s.serviceNameSource == null ? null : SERVICE_SOURCE_HANDLER.register(s.serviceNameSource);
      this.type = registerOrEmpty(TYPE_HANDLER, s.spanType);
      this.spanKind = registerOrEmpty(SPAN_KIND_HANDLER, s.spanKind);
      this.httpMethod = s.httpMethod == null ? null : HTTP_METHOD_HANDLER.register(s.httpMethod);
      this.httpEndpoint =
          s.httpEndpoint == null ? null : HTTP_ENDPOINT_HANDLER.register(s.httpEndpoint);
      this.grpcStatusCode =
          s.grpcStatusCode == null ? null : GRPC_STATUS_CODE_HANDLER.register(s.grpcStatusCode);
      this.httpStatusCode = s.httpStatusCode;
      this.synthetic = s.synthetic;
      this.traceRoot = s.traceRoot;
      populatePeerTags(s.peerTagSchema, s.peerTagValues);
      this.keyHash =
          hashOf(
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
              peerTagsBuffer);
    }

    /**
     * Fills {@link #peerTagsBuffer} with canonical UTF8 forms, applying {@code schema.handler(i)}
     * to each non-null value at the same index. No allocation when the schema/values are absent or
     * all values are null (buffer is just cleared).
     */
    private void populatePeerTags(PeerTagSchema schema, String[] values) {
      peerTagsBuffer.clear();
      if (schema == null || values == null) {
        return;
      }
      int n = schema.size();
      for (int i = 0; i < n; i++) {
        String v = values[i];
        if (v != null) {
          peerTagsBuffer.add(schema.handler(i).register(v));
        }
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
          && peerTagsEqual(peerTagsBuffer, e.peerTags)
          && Objects.equals(httpMethod, e.httpMethod)
          && Objects.equals(httpEndpoint, e.httpEndpoint)
          && Objects.equals(grpcStatusCode, e.grpcStatusCode);
    }

    /** Indexed list comparison -- avoids the iterator a {@code List.equals} would allocate. */
    private static boolean peerTagsEqual(List<UTF8BytesString> a, List<UTF8BytesString> b) {
      int n = a.size();
      if (n != b.size()) {
        return false;
      }
      for (int i = 0; i < n; i++) {
        if (!a.get(i).equals(b.get(i))) {
          return false;
        }
      }
      return true;
    }

    /**
     * Build a new entry from the currently-populated canonical fields. The peer-tag buffer is
     * copied into an immutable list so the entry's reference stays stable across subsequent {@link
     * #populate} calls.
     */
    AggregateEntry toEntry() {
      List<UTF8BytesString> snapshottedPeerTags;
      int n = peerTagsBuffer.size();
      if (n == 0) {
        snapshottedPeerTags = Collections.emptyList();
      } else if (n == 1) {
        snapshottedPeerTags = Collections.singletonList(peerTagsBuffer.get(0));
      } else {
        snapshottedPeerTags = new ArrayList<>(peerTagsBuffer);
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
          snapshottedPeerTags);
    }
  }

  // ----- helpers -----

  private static UTF8BytesString registerOrEmpty(
      PropertyCardinalityHandler handler, CharSequence value) {
    return value == null ? UTF8BytesString.EMPTY : handler.register(value);
  }

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
