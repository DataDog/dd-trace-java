package datadog.trace.common.metrics;

import datadog.metrics.api.Histogram;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.util.Hashtable;
import datadog.trace.util.LongHashingUtils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLongArray;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link datadog.trace.util.Hashtable} entry used by the aggregator thread.
 *
 * <p>Stores the canonical UTF8 label values that identify one aggregate row, plus the mutable
 * counter and histogram state for that row. Labels are canonicalized before hashing, so overflow
 * values replaced with the sentinel use the same hash and map to the same row.
 *
 * <p>Not thread-safe — all mutation is on the aggregator thread. Tests must call {@link
 * #resetCardinalityHandlers()} in setup to avoid cross-test handler pollution (handlers are
 * static); tests using {@link PeerTagSchema} must also call {@link
 * PeerTagSchema#resetHandlers(HealthMetrics)} on the schema instance.
 */
final class AggregateEntry extends Hashtable.Entry {

  private static final Logger log = LoggerFactory.getLogger(AggregateEntry.class);

  static final long ERROR_TAG = 0x8000000000000000L;
  static final long TOP_LEVEL_TAG = 0x4000000000000000L;

  private static final UTF8BytesString[] EMPTY_TAGS = new UTF8BytesString[0];

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

  // Schema-ordered "key:value" strings; "key:" prefix makes packing unambiguous without null slots.
  final UTF8BytesString[] additionalTags;

  // Recording state. Mutated only on the aggregator thread. Not thread-safe.
  private final Histogram okLatencies;

  // Null until first error; SerializingMetricWriter writes empty histogram form when null.
  @Nullable private Histogram errorLatencies;

  private int errorCount;
  private int hitCount;
  private int topLevelCount;
  private long duration;

  /** Field-bearing constructor. Package-private so {@link AggregateEntryTestUtils} can build expected entries. */
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
      List<UTF8BytesString> peerTags,
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
    this.okLatencies = Histogram.newHistogram();
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
    UTF8BytesString[] peerTagsArr = peerTagsList.toArray(new UTF8BytesString[0]);
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
            peerTagsArr,
            peerTagsArr.length,
            EMPTY_TAGS,
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
        peerTagsList,
        EMPTY_TAGS);
  }

  /**
   * Resets every cardinality handler's working set. Must be called on the aggregator thread.
   * Existing entries continue to hold their previously-issued {@link UTF8BytesString} references;
   * matches via content-equality so snapshots delivered after a reset still resolve to the existing
   * entries.
   */
  static void resetCardinalityHandlers() {
    resetCardinalityHandlers(HealthMetrics.NO_OP);
  }

  static void resetCardinalityHandlers(HealthMetrics healthMetrics) {
    reportIfBlocked(healthMetrics, RESOURCE_HANDLER);
    reportIfBlocked(healthMetrics, SERVICE_HANDLER);
    reportIfBlocked(healthMetrics, OPERATION_HANDLER);
    reportIfBlocked(healthMetrics, SERVICE_SOURCE_HANDLER);
    reportIfBlocked(healthMetrics, TYPE_HANDLER);
    reportIfBlocked(healthMetrics, SPAN_KIND_HANDLER);
    reportIfBlocked(healthMetrics, HTTP_METHOD_HANDLER);
    reportIfBlocked(healthMetrics, HTTP_ENDPOINT_HANDLER);
    reportIfBlocked(healthMetrics, GRPC_STATUS_CODE_HANDLER);
    PeerTagSchema.INTERNAL.resetHandlers(healthMetrics);
  }

  private static void reportIfBlocked(
      HealthMetrics healthMetrics, PropertyCardinalityHandler handler) {
    long blocked = handler.reset();
    if (blocked > 0) {
      if (handler.shouldWarnThisCycle()) {
        log.warn(
            "Cardinality limit reached for stats field '{}'; further values will be reported as blocked_by_tracer",
            handler.name);
      }
      healthMetrics.onTagCardinalityBlocked(handler.statsDTag(), blocked);
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
      int peerTagCount,
      UTF8BytesString[] additionalTags,
      int additionalTagCount) {
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
    // Additional tags are packed compactly in schema order (alphabetical by key); each carries its
    // "key:" prefix so the packed form is unambiguous without positional null slots.
    for (int i = 0; i < additionalTagCount; i++) {
      h = LongHashingUtils.addToHash(h, additionalTags[i]);
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
    return serviceSource.length() > 0;
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
    return httpMethod.length() > 0;
  }

  UTF8BytesString getHttpEndpoint() {
    return httpEndpoint;
  }

  /**
   * Whether the snapshot carried an HTTP endpoint. See {@link #hasServiceSource} for the contract.
   */
  boolean hasHttpEndpoint() {
    return httpEndpoint.length() > 0;
  }

  UTF8BytesString getGrpcStatusCode() {
    return grpcStatusCode;
  }

  /**
   * Whether the snapshot carried a gRPC status code. See {@link #hasServiceSource} for the
   * contract.
   */
  boolean hasGrpcStatusCode() {
    return grpcStatusCode.length() > 0;
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
   * @return the packed additional-tag values this entry recorded, as canonical {@code "key:value"}
   *     UTF8BytesStrings in schema (alphabetical-by-key) order. Only tags the span actually set are
   *     present (no null slots), so the length is the count of present tags -- empty when the span
   *     set none or no additional tags are configured.
   */
  UTF8BytesString[] getAdditionalTags() {
    return additionalTags;
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

  /**
   * Returns the entry's error latency histogram, or {@code null} if no error has been recorded yet.
   * Callers should treat null as "serialize as an empty histogram" (see {@link
   * SerializingMetricWriter}).
   */
  Histogram getErrorLatencies() {
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
        errorLatenciesForWrite().accept(d);
        ++errorCount;
      } else {
        okLatencies.accept(d);
      }
      this.duration += d;
    }
    return this;
  }

  /**
   * Clears the recording state. The OK histogram is reused; the error histogram (if allocated) is
   * reused too, but entries that never saw an error keep their {@code errorLatencies} field null.
   */
  @SuppressFBWarnings("AT_NONATOMIC_64BIT_PRIMITIVE")
  void clearAggregate() {
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
        && Objects.equals(peerTags, that.peerTags)
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
     * Reusable buffer of canonicalized peer-tag UTF8 forms. Cleared and refilled in {@link
     * #populate}; on miss, {@link #createEntry} copies it into an immutable list for the entry to
     * own. Zero allocation on the hit path. Sized lazily to the schema's tag count; resized if the
     * schema grows.
     */
    UTF8BytesString[] peerTagsBuffer = EMPTY_TAGS;

    int peerTagsSize = 0;

    /** Schema + per-key blocked sentinels for additional metric tags. Immutable. */
    final AdditionalTagsSchema additionalTagsSchema;

    /**
     * Reusable scratch for canonicalized additional-tag values, sized to the schema. Present values
     * are packed at the front in schema order (alphabetical by key); {@link #additionalTagsSize}
     * gives the count. Each entry is a {@code "key:value"} UTF8BytesString, so packing loses no
     * information -- the key prefix disambiguates which key a value belongs to. Mirrors the {@code
     * peerTagsBuffer + peerTagsSize} pattern. {@link #createEntry} copies the populated prefix into
     * the new entry.
     */
    final UTF8BytesString[] additionalTagsBuffer;

    int additionalTagsSize;

    long keyHash;

    Canonical(AdditionalTagsSchema additionalTagsSchema) {
      this.additionalTagsSchema = additionalTagsSchema;
      this.additionalTagsBuffer = new UTF8BytesString[additionalTagsSchema.size()];
    }

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
      populateAdditionalTags(s.additionalTagValues);
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
          peerTagsBuffer != null ? peerTagsBuffer : EMPTY_TAGS,
          peerTagsSize,
          additionalTagsBuffer,
          additionalTagsSize);
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
    private void populatePeerTags(@Nullable PeerTagSchema schema, @Nullable String[] values) {
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
     * Packs canonical {@code "key:value"} UTF8BytesStrings for each present slot of {@code values}
     * into the front of {@link #additionalTagsBuffer} (schema order), via {@link
     * AdditionalTagsSchema#register}, and sets {@link #additionalTagsSize}. The handler returns the
     * per-key blocked sentinel when the per-cycle value budget is exhausted.
     */
    private void populateAdditionalTags(@Nullable String[] values) {
      additionalTagsSize = 0;
      int n = additionalTagsBuffer.length;
      if (n == 0 || values == null) {
        return;
      }
      for (int i = 0; i < n; i++) {
        String v = values[i];
        if (v == null) {
          continue;
        }
        additionalTagsBuffer[additionalTagsSize++] = additionalTagsSchema.register(i, v);
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
          && traceRoot == e.traceRoot
          && additionalTagsEqual(additionalTagsBuffer, additionalTagsSize, e.additionalTags);
    }

    /** Compact compare: first {@code aSize} slots of {@code a} against the entry's packed array. */
    private static boolean additionalTagsEqual(
        UTF8BytesString[] a, int aSize, UTF8BytesString[] b) {
      if (aSize != b.length) {
        return false;
      }
      for (int i = 0; i < aSize; i++) {
        if (!a[i].equals(b[i])) {
          return false;
        }
      }
      return true;
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
      UTF8BytesString[] snapshottedAdditionalTags =
          additionalTagsSize == 0
              ? EMPTY_TAGS
              : Arrays.copyOf(additionalTagsBuffer, additionalTagsSize);
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
