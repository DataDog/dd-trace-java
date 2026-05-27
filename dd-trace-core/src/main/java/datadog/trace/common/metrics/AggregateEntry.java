package datadog.trace.common.metrics;

import datadog.metrics.api.Histogram;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.util.Hashtable;
import datadog.trace.util.LongHashingUtils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Hashtable entry for the consumer-side aggregator. Holds the UTF8-encoded label fields (the data
 * {@link SerializingMetricWriter} writes to the wire) plus the mutable counter / histogram state
 * for the key.
 *
 * <p>UTF8 canonicalization runs through per-field {@link PropertyCardinalityHandler}s (and {@link
 * TagCardinalityHandler}s for peer tags), which combine a UTF8 reuse cache with an optional
 * per-cycle cardinality limit (see {@link #LIMITS_ENABLED}). The critical property: hashing and
 * matching happen <b>after</b> canonicalization, so when limits are enabled and a field's budget is
 * exhausted, overflow values collapse to a {@code blocked_by_tracer} sentinel and land in the same
 * bucket rather than fragmenting. When limits are disabled (the default), the cache size is still
 * capped at the same budget but over-cap values get freshly-allocated {@link UTF8BytesString}s and
 * flow to distinct buckets.
 *
 * <p>The aggregator thread is the sole writer. {@link AggregateTable} holds a reusable {@link
 * Canonical} scratch buffer so the canonicalization itself doesn't allocate per lookup; on a miss
 * the buffer's references are copied into a fresh entry. On a hit nothing is allocated.
 *
 * <p>The handlers are reset on the aggregator thread every reporting cycle via {@link
 * #resetCardinalityHandlers()}.
 *
 * <p><b>Deliberate cohesion.</b> This class concentrates the per-field {@code
 * PropertyCardinalityHandler}/{@code TagCardinalityHandler} infrastructure, the canonicalized label
 * fields, the encoded {@code peerTags} list used by the serializer, the {@link Canonical} scratch
 * buffer, and the mutable counter/histogram aggregate state on a single object. The prior design
 * split label fields and aggregate state across separate {@code MetricKey} and {@code
 * AggregateMetric} instances, allocating both per unique key on miss; folding them yields one
 * allocation per unique key. The class is wider than its predecessors as a result, but that's the
 * trade we explicitly chose.
 *
 * <p><b>Thread-safety:</b> not thread-safe. Counter and histogram updates, cardinality-handler
 * registration, and {@link Canonical} use all run on the aggregator thread. Producer threads tag
 * durations via {@link #ERROR_TAG} / {@link #TOP_LEVEL_TAG} bits and hand them off through the
 * snapshot inbox. Test code uses {@link #of} which constructs entries without touching the
 * cardinality handlers.
 *
 * <p><b>Single-writer invariant relies on convention.</b> The aggregator thread is the only mutator
 * of this class and of {@link AggregateTable}. The {@code SuppressFBWarnings} below documents this
 * assumption but nothing enforces it at runtime -- a stray mutation from a different thread (e.g.
 * an HTTP-client callback) would corrupt counters, cardinality-handler state, or hashtable chains
 * silently. The {@code ClearSignal} routing in {@link Aggregator} is the explicit mechanism for
 * funneling cross-thread requests (e.g. {@code disable()}) back onto the aggregator thread; any new
 * entry point that mutates aggregate state must do the same.
 *
 * <p><b>One {@link ClientStatsAggregator} per JVM.</b> The {@code RESOURCE_HANDLER}/{@code
 * SERVICE_HANDLER}/... fields and {@link PeerTagSchema#INTERNAL} are {@code static}, so all
 * aggregator instances in a JVM share the same per-field cardinality budgets and {@code
 * blocked_by_tracer} sentinels. Production wires up exactly one aggregator (see {@link
 * MetricsAggregatorFactory}); tests that exercise this class must call {@link
 * #resetCardinalityHandlers()} in their setup to avoid cross-test pollution.
 */
@SuppressFBWarnings(
    value = {"AT_NONATOMIC_OPERATIONS_ON_SHARED_VARIABLE", "AT_STALE_THREAD_WRITE_OF_PRIMITIVE"},
    justification = "Explicitly not thread-safe. Accumulates counts and durations.")
final class AggregateEntry extends Hashtable.Entry {

  static final long ERROR_TAG = 0x8000000000000000L;
  static final long TOP_LEVEL_TAG = 0x4000000000000000L;

  /**
   * Whether cardinality limits substitute the {@code blocked_by_tracer} sentinel when a per-field
   * budget is exhausted. Read once at class init from {@link
   * Config#isTraceStatsCardinalityLimitsEnabled()} ({@code trace.stats.cardinality.limits.enabled},
   * default {@code false}) and threaded through every {@link PropertyCardinalityHandler} and {@link
   * TagCardinalityHandler} the class owns. With the flag off, the per-field tables still cap their
   * cache size at the same budget but over-cap values get freshly-allocated {@link
   * UTF8BytesString}s instead of the sentinel -- so the wire format never carries a {@code
   * blocked_by_tracer} value and entries don't collapse into a shared bucket.
   *
   * <p><b>Over-cap repeat tradeoff in disabled mode.</b> When the cap is exhausted and the flag is
   * off, over-cap values are not written into the current-cycle cache (it's full). A repeat of the
   * same over-cap value within the same cycle therefore re-walks both probe chains and allocates a
   * fresh {@code UTF8BytesString} -- it cannot promote into the cache to amortize subsequent calls.
   * The typical "stable working set + occasional outliers" workload is unaffected (working set fits
   * in the cap and stays cached); a workload with repeating over-cap values pays one allocation per
   * repeat. The prior cap sizing in {@link MetricCardinalityLimits} was chosen for the limiter role
   * and is appropriately conservative; if production shows cache thrashing in disabled mode, widen
   * the limits via a follow-up rather than changing the eviction strategy here.
   *
   * <p><b>Class-init caveat.</b> This field is {@code static final}, so its value is frozen for the
   * JVM at the first reference to {@code AggregateEntry}. Tests that want to exercise the
   * limits-enabled code path through {@link #RESOURCE_HANDLER} / {@link #SERVICE_HANDLER} / etc.
   * can't simply set Config and reload -- the static field captures whatever Config returned the
   * first time the class loaded. Construct {@link PropertyCardinalityHandler} or {@link
   * TagCardinalityHandler} directly with explicit {@code useBlockedSentinel} args (the convenience
   * constructors default to {@code true} for this reason) when targeted limits-on testing is
   * needed.
   */
  static final boolean LIMITS_ENABLED = Config.get().isTraceStatsCardinalityLimitsEnabled();

  // Per-field cardinality handlers. Limits live on MetricCardinalityLimits -- see that class for
  // per-field rationale.
  static final PropertyCardinalityHandler RESOURCE_HANDLER =
      new PropertyCardinalityHandler(MetricCardinalityLimits.RESOURCE, LIMITS_ENABLED);
  static final PropertyCardinalityHandler SERVICE_HANDLER =
      new PropertyCardinalityHandler(MetricCardinalityLimits.SERVICE, LIMITS_ENABLED);
  static final PropertyCardinalityHandler OPERATION_HANDLER =
      new PropertyCardinalityHandler(MetricCardinalityLimits.OPERATION, LIMITS_ENABLED);
  static final PropertyCardinalityHandler SERVICE_SOURCE_HANDLER =
      new PropertyCardinalityHandler(MetricCardinalityLimits.SERVICE_SOURCE, LIMITS_ENABLED);
  static final PropertyCardinalityHandler TYPE_HANDLER =
      new PropertyCardinalityHandler(MetricCardinalityLimits.TYPE, LIMITS_ENABLED);
  static final PropertyCardinalityHandler SPAN_KIND_HANDLER =
      new PropertyCardinalityHandler(MetricCardinalityLimits.SPAN_KIND, LIMITS_ENABLED);
  static final PropertyCardinalityHandler HTTP_METHOD_HANDLER =
      new PropertyCardinalityHandler(MetricCardinalityLimits.HTTP_METHOD, LIMITS_ENABLED);
  static final PropertyCardinalityHandler HTTP_ENDPOINT_HANDLER =
      new PropertyCardinalityHandler(MetricCardinalityLimits.HTTP_ENDPOINT, LIMITS_ENABLED);
  static final PropertyCardinalityHandler GRPC_STATUS_CODE_HANDLER =
      new PropertyCardinalityHandler(MetricCardinalityLimits.GRPC_STATUS_CODE, LIMITS_ENABLED);

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
  private final Histogram errorLatencies = Histogram.newHistogram();
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

  /**
   * Resets the per-cycle counters and histograms. Label fields ({@code resource}, {@code service},
   * ..., {@code peerTags}) are deliberately left intact -- they're the entry's bucket identity and
   * must persist so a subsequent snapshot with the same key reuses this entry instead of allocating
   * a fresh one. Entries that stay at {@code hitCount == 0} across a cycle are reaped by {@link
   * AggregateTable#expungeStaleAggregates}.
   */
  @SuppressFBWarnings("AT_NONATOMIC_64BIT_PRIMITIVE")
  void clear() {
    this.errorCount = 0;
    this.hitCount = 0;
    this.topLevelCount = 0;
    this.duration = 0;
    this.okLatencies.clear();
    this.errorLatencies.clear();
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
    PeerTagSchema.INTERNAL.resetCardinalityHandlers();
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
      List<UTF8BytesString> peerTags) {
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
    // indexed iteration -- avoids the iterator allocation a for-each over a List would do
    int peerTagCount = peerTags.size();
    for (int i = 0; i < peerTagCount; i++) {
      h = LongHashingUtils.addToHash(h, peerTags.get(i));
    }
    h = LongHashingUtils.addToHash(h, httpStatusCode);
    h = LongHashingUtils.addToHash(h, synthetic);
    h = LongHashingUtils.addToHash(h, traceRoot);
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

  /**
   * Whether the snapshot carried a service-source value. Encapsulates the EMPTY-as-absent
   * convention: optional fields use {@link UTF8BytesString#EMPTY} as the sentinel for "no value
   * captured" (see field comment) -- callers that need a presence check should go through this
   * predicate rather than comparing against {@code EMPTY} directly.
   */
  boolean hasServiceSource() {
    return serviceSource != UTF8BytesString.EMPTY;
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

  /**
   * Whether the snapshot carried an HTTP method. See {@link #hasServiceSource} for the contract.
   */
  boolean hasHttpMethod() {
    return httpMethod != UTF8BytesString.EMPTY;
  }

  UTF8BytesString getHttpEndpoint() {
    return httpEndpoint;
  }

  /**
   * Whether the snapshot carried an HTTP endpoint. See {@link #hasServiceSource} for the contract.
   */
  boolean hasHttpEndpoint() {
    return httpEndpoint != UTF8BytesString.EMPTY;
  }

  UTF8BytesString getGrpcStatusCode() {
    return grpcStatusCode;
  }

  /**
   * Whether the snapshot carried a gRPC status code. See {@link #hasServiceSource} for the
   * contract.
   */
  boolean hasGrpcStatusCode() {
    return grpcStatusCode != UTF8BytesString.EMPTY;
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
     * #populate}; on miss, {@link #toEntry} copies it into an immutable list for the entry to own.
     * Zero allocation on the hit path.
     */
    final ArrayList<UTF8BytesString> peerTagsBuffer = new ArrayList<>(4);

    long keyHash;

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
     * Fills {@link #peerTagsBuffer} with canonical UTF8 forms, applying the schema's per-tag
     * handler + warn-once notification at the same index. Skips null values rather than round-
     * tripping them through the handler (which would return EMPTY and be filtered out anyway).
     * Producer-side {@code capturePeerTagValues} produces sparse-null arrays, so the skip pays off
     * whenever a span carries only a subset of the configured peer tags.
     */
    private void populatePeerTags(PeerTagSchema schema, String[] values) {
      peerTagsBuffer.clear();
      if (schema == null || values == null) {
        return;
      }
      int n = schema.size();
      for (int i = 0; i < n; i++) {
        String value = values[i];
        if (value == null) {
          continue;
        }
        peerTagsBuffer.add(schema.register(i, value));
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
          && peerTagsEqual(peerTagsBuffer, e.peerTags)
          && httpStatusCode == e.httpStatusCode
          && synthetic == e.synthetic
          && traceRoot == e.traceRoot;
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
