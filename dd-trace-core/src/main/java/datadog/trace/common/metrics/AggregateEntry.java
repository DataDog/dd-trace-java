package datadog.trace.common.metrics;

import datadog.metrics.api.Histogram;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.util.Hashtable;
import datadog.trace.util.LongHashingUtils;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/**
 * {@link datadog.trace.util.Hashtable} entry used by the aggregator thread.
 *
 * <p>Stores the canonical UTF8 label values that identify one aggregate row, plus the mutable
 * counter and histogram state for that row. Labels are canonicalized before hashing, so overflow
 * values replaced with the sentinel use the same hash and map to the same row.
 *
 * <p>Not thread-safe — all mutation is on the aggregator thread. Tests must call {@link
 * #resetCardinalityHandlers()} in setup to avoid cross-test handler pollution (handlers are
 * static); tests using {@link PeerTagSchema} must also call {@code PeerTagSchema#resetHandlers} on
 * the schema instance.
 */
public final class AggregateEntry extends Hashtable.Entry {

  static final long ERROR_TAG = 0x8000000000000000L;
  static final long TOP_LEVEL_TAG = 0x4000000000000000L;

  private static final UTF8BytesString[] EMPTY_TAGS = new UTF8BytesString[0];

  // Sentinel substitution is disabled until per-component config is wired in a follow-up PR.
  // Tests that need sentinel mode should pass useBlockedSentinel=true explicitly.
  static final boolean LIMITS_ENABLED = false;

  // Per-field cardinality handlers. Limits live on MetricCardinalityLimits -- see that class for
  // per-field rationale.
  static final PropertyCardinalityHandler RESOURCE_HANDLER =
      new PropertyCardinalityHandler("resource", MetricCardinalityLimits.RESOURCE, LIMITS_ENABLED);
  static final PropertyCardinalityHandler SERVICE_HANDLER =
      new PropertyCardinalityHandler("service", MetricCardinalityLimits.SERVICE, LIMITS_ENABLED);
  static final PropertyCardinalityHandler OPERATION_HANDLER =
      new PropertyCardinalityHandler(
          "operation", MetricCardinalityLimits.OPERATION, LIMITS_ENABLED);
  static final PropertyCardinalityHandler SERVICE_SOURCE_HANDLER =
      new PropertyCardinalityHandler(
          "service_source", MetricCardinalityLimits.SERVICE_SOURCE, LIMITS_ENABLED);
  static final PropertyCardinalityHandler TYPE_HANDLER =
      new PropertyCardinalityHandler("type", MetricCardinalityLimits.TYPE, LIMITS_ENABLED);
  static final PropertyCardinalityHandler SPAN_KIND_HANDLER =
      new PropertyCardinalityHandler(
          "span_kind", MetricCardinalityLimits.SPAN_KIND, LIMITS_ENABLED);
  static final PropertyCardinalityHandler HTTP_METHOD_HANDLER =
      new PropertyCardinalityHandler(
          "http_method", MetricCardinalityLimits.HTTP_METHOD, LIMITS_ENABLED);
  static final PropertyCardinalityHandler HTTP_ENDPOINT_HANDLER =
      new PropertyCardinalityHandler(
          "http_endpoint", MetricCardinalityLimits.HTTP_ENDPOINT, LIMITS_ENABLED);
  static final PropertyCardinalityHandler GRPC_STATUS_CODE_HANDLER =
      new PropertyCardinalityHandler(
          "grpc_status_code", MetricCardinalityLimits.GRPC_STATUS_CODE, LIMITS_ENABLED);

  // Single authoritative list used by resetCardinalityHandlers(). populateFrom() and hashOf() keep
  // named access for readability and to avoid per-span iteration overhead; this array ensures the
  // reset site stays in sync even if a new field is added without updating the loop by name.
  static final PropertyCardinalityHandler[] FIELD_HANDLERS = {
    RESOURCE_HANDLER,
    SERVICE_HANDLER,
    OPERATION_HANDLER,
    SERVICE_SOURCE_HANDLER,
    TYPE_HANDLER,
    SPAN_KIND_HANDLER,
    HTTP_METHOD_HANDLER,
    HTTP_ENDPOINT_HANDLER,
    GRPC_STATUS_CODE_HANDLER,
  };

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
  final List<UTF8BytesString> peerTags;

  // Mutable aggregate state -- single-thread (aggregator) writer.
  private final Histogram okLatencies = Histogram.newHistogram();

  /**
   * Lazily allocated on the first recorded error. Most entries never see an error and keep this
   * null for life; {@link SerializingMetricWriter} writes a cached empty-histogram form when null
   * to keep the wire payload identical. Once allocated, it survives {@link #clear()} (cleared, not
   * nulled) since an entry that errored once tends to error again.
   */
  @Nullable private Histogram errorLatencies;

  private int errorCount;
  private int hitCount;
  private int topLevelCount;
  private long okDuration;
  private long errorDuration;

  /**
   * Field-bearing constructor. Package-private so {@link AggregateEntryTestUtils} can build
   * expected entries.
   */
  AggregateEntry(
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
      errorDuration += tagAndDuration;
      ++errorCount;
    } else {
      okLatencies.accept(tagAndDuration);
      okDuration += tagAndDuration;
    }
    return this;
  }

  public int getErrorCount() {
    return errorCount;
  }

  public int getHitCount() {
    return hitCount;
  }

  public int getTopLevelCount() {
    return topLevelCount;
  }

  public long getDuration() {
    return okDuration + errorDuration;
  }

  public long getOkDuration() {
    return okDuration;
  }

  public long getErrorDuration() {
    return errorDuration;
  }

  public Histogram getOkLatencies() {
    return okLatencies;
  }

  /**
   * Returns the entry's error-latency histogram, or {@code null} if no error has been recorded.
   * Callers serializing this should treat {@code null} as "emit a cached empty histogram"; see
   * {@link SerializingMetricWriter}.
   */
  @Nullable
  public Histogram getErrorLatencies() {
    return errorLatencies;
  }

  /** Lazy-allocates {@link #errorLatencies} on the first error. */
  private Histogram errorLatenciesForWrite() {
    Histogram h = errorLatencies;
    if (h == null) {
      h = Histogram.newHistogram();
      errorLatencies = h;
    }
    return h;
  }

  /**
   * Resets the per-cycle counters and histograms. Label fields ({@code resource}, {@code service},
   * ..., {@code peerTags}) are deliberately left intact -- they're the entry's bucket identity and
   * must persist so a subsequent snapshot with the same key reuses this entry instead of allocating
   * a fresh one. Entries that stay at {@code hitCount == 0} across a cycle are reaped by {@link
   * AggregateTable#expungeStaleAggregates}.
   */
  void clear() {
    this.errorCount = 0;
    this.hitCount = 0;
    this.topLevelCount = 0;
    this.okDuration = 0;
    this.errorDuration = 0;
    this.okLatencies.clear();
    // errorLatencies stays null on entries that never errored. Only clear if it was allocated.
    if (this.errorLatencies != null) {
      this.errorLatencies.clear();
    }
  }

  /** Resets the static per-field cardinality handlers. Does not cover {@link PeerTagSchema}. */
  static void resetCardinalityHandlers() {
    for (PropertyCardinalityHandler handler : FIELD_HANDLERS) {
      handler.reset();
    }
  }

  /**
   * 64-bit lookup hash, computed over UTF8-encoded fields so that cardinality-blocked values (which
   * all canonicalize to the same sentinel {@link UTF8BytesString}) collide in the same bucket.
   * {@link UTF8BytesString#hashCode()} returns the underlying String hash, so entries built via
   * {@link #of} produce the same hash as entries built from a snapshot with matching content.
   *
   * <p>Field order intentionally mirrors {@link Canonical#matches} -- UTF8 fields first (highest
   * cardinality first for matches' short-circuit benefit), then the peer-tag list, then the
   * primitives. The hash itself is order-stable across all callers; the lockstep ordering is purely
   * for readability when reasoning about lookup and equality in tandem.
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
      int peerTagCount) {
    long h = 0;
    h = LongHashingUtils.addToHash(h, resource);
    h = LongHashingUtils.addToHash(h, service);
    h = LongHashingUtils.addToHash(h, operationName);
    h = LongHashingUtils.addToHash(h, serviceSource);
    h = LongHashingUtils.addToHash(h, type);
    h = LongHashingUtils.addToHash(h, spanKind);
    h = LongHashingUtils.addToHash(h, httpMethod);
    h = LongHashingUtils.addToHash(h, httpEndpoint);
    h = LongHashingUtils.addToHash(h, grpcStatusCode);
    for (int i = 0; i < peerTagCount; i++) {
      h = LongHashingUtils.addToHash(h, peerTags[i]);
    }
    h = LongHashingUtils.addToHash(h, httpStatusCode);
    h = LongHashingUtils.addToHash(h, synthetic);
    h = LongHashingUtils.addToHash(h, traceRoot);
    return h;
  }

  // Accessors for SerializingMetricWriter.
  public UTF8BytesString getResource() {
    return resource;
  }

  public UTF8BytesString getService() {
    return service;
  }

  public UTF8BytesString getOperationName() {
    return operationName;
  }

  public UTF8BytesString getServiceSource() {
    return serviceSource;
  }

  /**
   * Whether the snapshot carried a service-source value. Encapsulates the EMPTY-as-absent
   * convention: optional fields use {@link UTF8BytesString#EMPTY} as the sentinel for "no value
   * captured" (see field comment) -- callers that need a presence check should go through this
   * predicate rather than comparing against {@code EMPTY} directly.
   */
  public boolean hasServiceSource() {
    return serviceSource.length() > 0;
  }

  public UTF8BytesString getType() {
    return type;
  }

  public UTF8BytesString getSpanKind() {
    return spanKind;
  }

  public UTF8BytesString getHttpMethod() {
    return httpMethod;
  }

  /**
   * Whether the snapshot carried an HTTP method. See {@link #hasServiceSource} for the contract.
   */
  public boolean hasHttpMethod() {
    return httpMethod.length() > 0;
  }

  public UTF8BytesString getHttpEndpoint() {
    return httpEndpoint;
  }

  /**
   * Whether the snapshot carried an HTTP endpoint. See {@link #hasServiceSource} for the contract.
   */
  public boolean hasHttpEndpoint() {
    return httpEndpoint.length() > 0;
  }

  public UTF8BytesString getGrpcStatusCode() {
    return grpcStatusCode;
  }

  /**
   * Whether the snapshot carried a gRPC status code. See {@link #hasServiceSource} for the
   * contract.
   */
  public boolean hasGrpcStatusCode() {
    return grpcStatusCode.length() > 0;
  }

  public int getHttpStatusCode() {
    return httpStatusCode;
  }

  public boolean isSynthetics() {
    return synthetic;
  }

  public boolean isTraceRoot() {
    return traceRoot;
  }

  public List<UTF8BytesString> getPeerTags() {
    return peerTags;
  }

  // Production AggregateEntry intentionally has no equals/hashCode override -- AggregateTable
  // bucketing uses keyHash + Canonical.matches and never invokes Object.equals. For tests that
  // need value-equality (Spock argument matchers), use AggregateEntryTestUtils in src/test.

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
     * Reusable buffer of canonicalized peer-tag UTF8 forms. Cleared and refilled in {@link
     * #populate}; on miss, {@link #createEntry} copies it into an immutable list for the entry to
     * own. Zero allocation on the hit path. Sized lazily to the schema's tag count; resized if the
     * schema grows.
     */
    UTF8BytesString[] peerTagsBuffer = EMPTY_TAGS;

    int peerTagsSize = 0;

    long keyHash;

    /** Canonicalize all fields from {@code s} through the handlers into this buffer. */
    void populateFrom(SpanSnapshot s) {
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
              peerTagsBuffer,
              peerTagsSize);
    }

    /**
     * Replaces the current peer-tag buffer contents with the canonical UTF8 forms for this
     * snapshot.
     *
     * <p>Each non-null value is canonicalized with the handler at the same schema index. Null
     * values are skipped because they would canonicalize to {@link UTF8BytesString#EMPTY} and be
     * filtered out anyway. Producer-side {@code capturePeerTagValues} produces sparse-null arrays,
     * so the skip pays off whenever a span carries only a subset of the configured peer tags.
     */
    private void populatePeerTags(PeerTagSchema schema, String[] values) {
      peerTagsSize = 0;
      if (schema == null || values == null) {
        return;
      }
      int n = Math.min(schema.size(), values.length);
      if (peerTagsBuffer.length < n) {
        peerTagsBuffer = new UTF8BytesString[n];
      }
      for (int i = 0; i < n; i++) {
        String value = values[i];
        if (value == null) {
          continue;
        }
        peerTagsBuffer[peerTagsSize++] = schema.register(i, value);
      }
    }

    /**
     * Whether this canonicalized snapshot matches the given entry. Compares UTF8 fields via
     * content-equality (so an entry surviving a handler reset still matches a freshly-canonicalized
     * snapshot of the same content).
     *
     * <p>Field order is cardinality-tuned: resource / service / operationName first because they
     * vary most across collisions, then the remaining UTF8 fields, then the peer-tag list
     * comparison (slowest), then the primitives. All UTF8 fields are non-null by the EMPTY-
     * sentinel invariant (see field comments above), so direct {@code a.equals(b)} is safe.
     */
    boolean matches(AggregateEntry e) {
      return resource.equals(e.resource)
          && service.equals(e.service)
          && operationName.equals(e.operationName)
          && serviceSource.equals(e.serviceSource)
          && type.equals(e.type)
          && spanKind.equals(e.spanKind)
          && httpMethod.equals(e.httpMethod)
          && httpEndpoint.equals(e.httpEndpoint)
          && grpcStatusCode.equals(e.grpcStatusCode)
          && peerTagsEqual(peerTagsBuffer, peerTagsSize, e.peerTags)
          && httpStatusCode == e.httpStatusCode
          && synthetic == e.synthetic
          && traceRoot == e.traceRoot;
    }

    private static boolean peerTagsEqual(UTF8BytesString[] a, int aSize, List<UTF8BytesString> b) {
      if (aSize != b.size()) {
        return false;
      }
      for (int i = 0; i < aSize; i++) {
        if (!a[i].equals(b.get(i))) {
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
    AggregateEntry createEntry() {
      List<UTF8BytesString> snapshottedPeerTags;
      int n = peerTagsSize;
      if (n == 0) {
        snapshottedPeerTags = Collections.emptyList();
      } else if (n == 1) {
        snapshottedPeerTags = Collections.singletonList(peerTagsBuffer[0]);
      } else {
        snapshottedPeerTags = Arrays.asList(Arrays.copyOf(peerTagsBuffer, n));
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

  /** Direct {@link UTF8BytesString} creation that bypasses the cardinality handlers. */
  static UTF8BytesString createUtf8(CharSequence cs) {
    if (cs == null) {
      return UTF8BytesString.EMPTY;
    }
    if (cs instanceof UTF8BytesString) {
      return (UTF8BytesString) cs;
    }
    return UTF8BytesString.create(cs.toString());
  }
}
