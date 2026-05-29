package datadog.trace.common.metrics;

import static datadog.trace.bootstrap.instrumentation.api.UTF8BytesString.EMPTY;

import datadog.metrics.api.Histogram;
import datadog.trace.api.Pair;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.util.Hashtable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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
 * <p><b>Deliberate cohesion.</b> This class concentrates five responsibilities -- the static UTF8
 * caches, the canonicalized label fields, the raw {@code peerTagNames}/{@code peerTagValues} arrays
 * used by {@link #matches}, the pre-encoded {@code peerTags} list used by the serializer, and the
 * mutable counter/histogram aggregate state -- on a single object. The prior design split the label
 * fields and aggregate state across separate {@code MetricKey} and {@code AggregateMetric}
 * instances, allocating both per unique key on miss; folding them yields one allocation per unique
 * key. The class is wider than its predecessors as a result, but that's the trade we explicitly
 * chose.
 *
 * <p><b>Required vs optional field absence.</b> Required label fields ({@code resource}, {@code
 * service}, {@code operationName}, {@code type}, {@code spanKind}) canonicalize a {@code null}
 * snapshot value into {@link UTF8BytesString#EMPTY} via {@link #canonicalize} -- they are never
 * {@code null} on a constructed entry. Optional label fields ({@code serviceSource}, {@code
 * httpMethod}, {@code httpEndpoint}, {@code grpcStatusCode}) stay {@code null} on the entry when
 * the snapshot value was {@code null}; the serializer uses {@code != null} to decide whether to
 * emit them on the wire. {@link #contentEquals} treats {@code null} and length-0 as equivalent so
 * {@link #matches} works against either form.
 *
 * <p><b>Not thread-safe.</b> Counter and histogram updates are performed by the single aggregator
 * thread; producer threads tag durations via {@link #ERROR_TAG} / {@link #TOP_LEVEL_TAG} bits and
 * hand them off through the snapshot inbox.
 *
 * <p><b>Single-writer invariant relies on convention.</b> The aggregator thread is the only mutator
 * of this class and of {@link AggregateTable}. Nothing enforces this at runtime -- a stray mutation
 * from a different thread (e.g. an HTTP-client callback) would corrupt counters or hashtable chains
 * silently. The {@code ClearSignal} routing in {@link Aggregator} is the explicit mechanism for
 * funneling cross-thread requests (e.g. {@code disable()}) back onto the aggregator thread; any new
 * entry point that mutates aggregate state must do the same.
 */
final class AggregateEntry extends Hashtable.Entry {

  static final long ERROR_TAG = 0x8000000000000000L;
  static final long TOP_LEVEL_TAG = 0x4000000000000000L;

  // UTF8 caches. Package-private so ConflatingMetricsAggregator's fillSlot can canonicalize
  // producer-side via the same caches the entry constructor used to consult.
  static final DDCache<String, UTF8BytesString> RESOURCE_CACHE = DDCaches.newFixedSizeCache(32);
  static final DDCache<String, UTF8BytesString> SERVICE_CACHE = DDCaches.newFixedSizeCache(32);
  static final DDCache<String, UTF8BytesString> OPERATION_CACHE = DDCaches.newFixedSizeCache(64);
  static final DDCache<String, UTF8BytesString> SERVICE_SOURCE_CACHE =
      DDCaches.newFixedSizeCache(16);
  static final DDCache<String, UTF8BytesString> TYPE_CACHE = DDCaches.newFixedSizeCache(8);
  static final DDCache<String, UTF8BytesString> SPAN_KIND_CACHE = DDCaches.newFixedSizeCache(16);
  static final DDCache<String, UTF8BytesString> HTTP_METHOD_CACHE = DDCaches.newFixedSizeCache(8);
  static final DDCache<String, UTF8BytesString> HTTP_ENDPOINT_CACHE =
      DDCaches.newFixedSizeCache(32);
  static final DDCache<String, UTF8BytesString> GRPC_STATUS_CODE_CACHE =
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

  /** Whether the root span carried the {@code synthetics} origin tag (synthetic-monitoring run). */
  private final boolean synthetic;

  /** Whether this span is the trace root ({@code parentId == 0}). */
  private final boolean traceRoot;

  // Peer tags carried in two forms: parallel String[] arrays mirroring the snapshot's (schema +
  // values) shape for matches(), and pre-encoded List<UTF8BytesString> ("name:value") for the
  // serializer. peerTagNames is the schema's names array (shared by-reference when the schema
  // hasn't been replaced); peerTagValues is the per-span String[] parallel to it.
  //
  // Package-private so the in-package test helper (AggregateEntryTestUtils) can compare entries
  // by raw layout; production access comes from this class's own matches() + constructor.
  @Nullable final String[] peerTagNames;
  @Nullable final String[] peerTagValues;
  private final List<UTF8BytesString> peerTags;

  // Mutable aggregate state -- single-thread (consumer/aggregator) writer.
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
  private long duration;

  /** Hot-path constructor for the producer/consumer flow. Slot fields are already canonical. */
  AggregateEntry(SpanSnapshot s) {
    super(s.keyHash);
    // The slot's string fields are already canonical UTF8BytesString instances (canonicalized by
    // the producer in fillSlot via the same caches we used to consult here). Just copy refs;
    // matches() can do identity comparison instead of contentEquals.
    this.resource = s.resourceName;
    this.service = s.serviceName;
    this.operationName = s.operationName;
    this.serviceSource = s.serviceNameSource;
    this.type = s.spanType;
    this.spanKind = s.spanKind;
    this.httpMethod = s.httpMethod;
    this.httpEndpoint = s.httpEndpoint;
    this.grpcStatusCode = s.grpcStatusCode;
    this.httpStatusCode = s.httpStatusCode;
    this.synthetic = s.synthetic;
    this.traceRoot = s.traceRoot;
    this.peerTagNames = s.peerTagSchema == null ? null : s.peerTagSchema.names;
    // The slot's peerTagValues is a reusable scratch buffer owned by the ring -- the producer
    // overwrites it on every claim of this slot. Snapshot it here by value so this entry's
    // identity doesn't drift when the slot is reclaimed.
    this.peerTagValues =
        s.peerTagSchema == null ? null : Arrays.copyOf(s.peerTagValues, s.peerTagValues.length);
    this.peerTags = materializePeerTags(this.peerTagNames, this.peerTagValues);
  }

  /**
   * Records a single hit. {@code tagAndDuration} carries the duration nanos with optional {@link
   * #ERROR_TAG} / {@link #TOP_LEVEL_TAG} bits OR-ed in.
   */
  void recordOneDuration(long tagAndDuration) {
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
   * Returns the entry's error-latency histogram, or {@code null} if no error has been recorded.
   * Callers serializing this should treat {@code null} as "emit a cached empty histogram"; see
   * {@link SerializingMetricWriter}.
   */
  @Nullable
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
   * Resets the per-cycle counters and histograms. Label fields ({@code resource}, {@code service},
   * ..., {@code peerTagNames}, {@code peerTagValues}) are deliberately left intact -- they're the
   * entry's bucket identity and must persist so a subsequent snapshot with the same key reuses this
   * entry instead of allocating a fresh one. Entries that stay at {@code hitCount == 0} across a
   * cycle are reaped by {@link AggregateTable#expungeStaleAggregates}.
   */
  void clear() {
    this.errorCount = 0;
    this.hitCount = 0;
    this.topLevelCount = 0;
    this.duration = 0;
    this.okLatencies.clear();
    // errorLatencies stays null on entries that never errored. Only clear if it was allocated.
    if (this.errorLatencies != null) {
      this.errorLatencies.clear();
    }
  }

  boolean matches(SpanSnapshot s) {
    // All string fields are canonical UTF8BytesString references from the per-field DDCaches.
    // Same content => same canonical instance => identity comparison suffices.
    // peerTagSchema is also shared by reference (producer reads cachedPeerTagSchema), so its
    // names array reference is stable too. peerTagValues stays content-compared since values
    // vary per publish and aren't canonicalized.
    return httpStatusCode == s.httpStatusCode
        && synthetic == s.synthetic
        && traceRoot == s.traceRoot
        && resource == s.resourceName
        && service == s.serviceName
        && operationName == s.operationName
        && serviceSource == s.serviceNameSource
        && type == s.spanType
        && spanKind == s.spanKind
        // PeerTagSchema instances aren't guaranteed to be shared by reference across all
        // producers (cachedPeerTagSchema can be swapped during reconcile; tests build fresh
        // instances), so compare names by content. The same-reference case short-circuits to
        // O(1) via Arrays.equals's a==b fast path.
        && Arrays.equals(peerTagNames, s.peerTagSchema == null ? null : s.peerTagSchema.names)
        && (peerTagNames == null || Arrays.equals(peerTagValues, s.peerTagValues))
        && httpMethod == s.httpMethod
        && httpEndpoint == s.httpEndpoint
        && grpcStatusCode == s.grpcStatusCode;
  }

  /**
   * Pre-checks {@link #keyHash} against {@code keyHash} before delegating to {@link
   * #matches(SpanSnapshot)}. The hash check is cheap and rules out most mismatches without touching
   * the field-by-field comparison.
   */
  boolean matches(long keyHash, SpanSnapshot s) {
    return this.keyHash == keyHash && matches(s);
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

  // Production AggregateEntry intentionally has no equals/hashCode override -- AggregateTable
  // bucketing uses keyHash + matches(SpanSnapshot) directly and never invokes Object.equals.
  // For tests that need value-equality (Spock argument matchers), use AggregateEntryTestUtils in
  // src/test, which provides equals/hashCode helpers without exposing the contract in production.

  // ----- helpers -----

  static UTF8BytesString canonicalize(
      DDCache<String, UTF8BytesString> cache, CharSequence charSeq) {
    // Treat null and length-zero as the same canonical EMPTY instance so the producer-side
    // canonicalization produces identity-equal results for null/"" inputs. matches() now uses
    // identity comparison, so the previous content-equals collapse needs to happen here.
    if (charSeq == null || charSeq.length() == 0) {
      return EMPTY;
    }
    if (charSeq instanceof UTF8BytesString) {
      return (UTF8BytesString) charSeq;
    }
    return cache.computeIfAbsent(charSeq.toString(), UTF8BytesString::create);
  }

  /**
   * Like {@link #canonicalize} but returns {@code null} for a {@code null} input (rather than
   * {@link UTF8BytesString#EMPTY}). Used for the four optional fields so the serializer can
   * distinguish "absent" via a {@code != null} check and elide the field on the wire.
   *
   * <p>The {@code instanceof UTF8BytesString} short-circuit is dead code for {@link
   * SpanSnapshot#httpMethod}/{@code httpEndpoint}/{@code grpcStatusCode} (statically {@code
   * String}) but live for {@link SpanSnapshot#serviceNameSource} ({@link CharSequence}); keeping a
   * single helper keeps the constructor consistent.
   */
  @Nullable
  static UTF8BytesString canonicalizeOptional(
      DDCache<String, UTF8BytesString> cache, @Nullable CharSequence charSeq) {
    // Treat null and length-zero the same: both map to null (i.e. "absent"). matches() uses
    // identity comparison, so the prior content-equals collapse needs to happen here.
    if (charSeq == null || charSeq.length() == 0) {
      return null;
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
    if (a == null || a.length() == 0) {
      return b == null || b.length() == 0;
    }
    // UTF8BytesString.toString() returns the underlying String -- O(1), no allocation.
    return b != null && a.toString().contentEquals(b);
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
