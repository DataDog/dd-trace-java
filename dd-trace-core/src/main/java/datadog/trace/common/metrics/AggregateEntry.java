package datadog.trace.common.metrics;

import datadog.metrics.api.Histogram;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.util.Hashtable;
import datadog.trace.util.LongHashingUtils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLongArray;
import javax.annotation.Nullable;

/**
 * Aggregator hashtable entry: UTF8 label fields + counter/histogram state.
 *
 * <p>Public so the cross-package OTLP writer ({@code datadog.trace.core.otlp.metrics}) can read the
 * per-entry accessors.
 */
public final class AggregateEntry extends Hashtable.Entry {

  static final long ERROR_TAG = 0x8000000000000000L;
  static final long TOP_LEVEL_TAG = 0x4000000000000000L;

  private static final UTF8BytesString[] EMPTY_TAGS = new UTF8BytesString[0];

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

  // Recording state (this field through errorDuration below) is thread-confined: the entry is
  // mutated only on the aggregator thread, so these fields are intentionally unsynchronized and not
  // thread-safe. Producers hand off immutable SpanSnapshots; only the aggregator thread records
  // into the entry.
  private final Histogram okLatencies;

  // Null until first error; SerializingMetricWriter writes empty histogram form when null.
  @Nullable private Histogram errorLatencies;

  private int errorCount;
  private int hitCount;
  private int topLevelCount;
  // Tracked separately for per-outcome OTLP histogram sums; getDuration() returns the sum.
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
    h = LongHashingUtils.addToHash(h, peerTags, peerTagCount);
    h = LongHashingUtils.addToHash(h, additionalTags, additionalTagCount);
    h = LongHashingUtils.addToHash(h, httpStatusCode);
    h = LongHashingUtils.addToHash(h, synthetic);
    h = LongHashingUtils.addToHash(h, traceRoot);
    return h;
  }

  // Accessors for SerializingMetricWriter and the cross-package OTLP writer.
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

  /**
   * @return the packed additional-tag values this entry recorded, as canonical {@code "key:value"}
   *     UTF8BytesStrings in schema (alphabetical-by-key) order. Only tags the span actually set are
   *     present (no null slots), so the length is the count of present tags -- empty when the span
   *     set none or no additional tags are configured.
   */
  public UTF8BytesString[] getAdditionalTags() {
    return additionalTags;
  }

  // ----- recording state accessors -----

  public int getHitCount() {
    return hitCount;
  }

  public int getErrorCount() {
    return errorCount;
  }

  public int getTopLevelCount() {
    return topLevelCount;
  }

  /**
   * Total recorded duration for the native msgpack path -- the sum of {@link #getOkDuration()} and
   * {@link #getErrorDuration()}. OK and error durations are tracked separately so the OTLP writer
   * can emit per-outcome histogram sums.
   */
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
   * Returns the entry's error latency histogram, or {@code null} if no error has been recorded yet.
   * Callers should treat null as "serialize as an empty histogram" (see {@link
   * SerializingMetricWriter}).
   */
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
   * Records a single hit. {@code tagAndDuration} carries the duration nanos with optional {@link
   * #ERROR_TAG} / {@link #TOP_LEVEL_TAG} bits OR-ed in.
   */
  @SuppressFBWarnings(
      value = "AT_NONATOMIC_OPERATIONS_ON_SHARED_VARIABLE",
      justification =
          "Single-writer by design: recording counters are mutated only on the aggregator thread"
              + " (see class javadoc); no cross-thread atomicity guarantee is needed.")
  AggregateEntry recordOneDuration(long tagAndDuration) {
    hitCount++;
    if ((tagAndDuration & TOP_LEVEL_TAG) == TOP_LEVEL_TAG) {
      tagAndDuration ^= TOP_LEVEL_TAG;
      topLevelCount++;
    }
    if ((tagAndDuration & ERROR_TAG) == ERROR_TAG) {
      tagAndDuration ^= ERROR_TAG;
      errorLatenciesForWrite().accept(tagAndDuration);
      errorDuration += tagAndDuration;
      errorCount++;
    } else {
      okLatencies.accept(tagAndDuration);
      okDuration += tagAndDuration;
    }
    return this;
  }

  /**
   * Records {@code count} durations from {@code durations} (positions 0..count-1). Used by
   * integration tests; production code uses {@link #recordOneDuration}.
   */
  @SuppressFBWarnings(
      value = "AT_NONATOMIC_OPERATIONS_ON_SHARED_VARIABLE",
      justification =
          "Single-writer by design: recording counters are mutated only on the aggregator thread"
              + " (see class javadoc); no cross-thread atomicity guarantee is needed.")
  AggregateEntry recordDurations(int count, AtomicLongArray durations) {
    this.hitCount += count;
    for (int i = 0; i < count && i < durations.length(); ++i) {
      long d = durations.getAndSet(i, 0);
      if ((d & TOP_LEVEL_TAG) == TOP_LEVEL_TAG) {
        d ^= TOP_LEVEL_TAG;
        topLevelCount++;
      }
      if ((d & ERROR_TAG) == ERROR_TAG) {
        d ^= ERROR_TAG;
        errorLatenciesForWrite().accept(d);
        this.errorDuration += d;
        errorCount++;
      } else {
        okLatencies.accept(d);
        this.okDuration += d;
      }
    }
    return this;
  }

  /**
   * Clears the recording state. The OK histogram is reused; the error histogram (if allocated) is
   * reused too, but entries that never saw an error keep their {@code errorLatencies} field null.
   */
  @SuppressFBWarnings(
      value = {"AT_NONATOMIC_64BIT_PRIMITIVE", "AT_STALE_THREAD_WRITE_OF_PRIMITIVE"},
      justification =
          "Single-writer by design: recording counters are reset only on the aggregator thread"
              + " (see class javadoc); no cross-thread visibility guarantee is needed.")
  void clearAggregate() {
    this.errorCount = 0;
    this.hitCount = 0;
    this.topLevelCount = 0;
    this.okDuration = 0;
    this.errorDuration = 0;
    this.okLatencies.clear();
    if (this.errorLatencies != null) {
      this.errorLatencies.clear();
    }
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

    /** Core per-field cardinality handlers; owned by the enclosing {@link AggregateTable}. */
    final CoreHandlers handlers;

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

    Canonical(CoreHandlers handlers, AdditionalTagsSchema additionalTagsSchema) {
      this.handlers = handlers;
      this.additionalTagsSchema = additionalTagsSchema;
      this.additionalTagsBuffer = new UTF8BytesString[additionalTagsSchema.size()];
    }

    /** Canonicalize all fields from {@code s} through the handlers into this buffer. */
    void populateFrom(SpanSnapshot s) {
      this.resource = handlers.resource.register(s.resourceName);
      this.service = handlers.service.register(s.serviceName);
      this.operationName = handlers.operation.register(s.operationName);
      this.serviceSource = handlers.serviceSource.register(s.serviceNameSource);
      this.type = handlers.type.register(s.spanType);
      this.spanKind = handlers.spanKind.register(s.spanKind);
      this.httpMethod = handlers.httpMethod.register(s.httpMethod);
      this.httpEndpoint = handlers.httpEndpoint.register(s.httpEndpoint);
      this.grpcStatusCode = handlers.grpcStatusCode.register(s.grpcStatusCode);
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
          peerTagsBuffer,
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
      // values is captured against the same additionalTagsSchema, so it is either null or exactly
      // schema.size() long (== additionalTagsBuffer.length); guard the array we actually index.
      if (values == null || values.length == 0) {
        return;
      }
      int n = additionalTagsBuffer.length;
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
