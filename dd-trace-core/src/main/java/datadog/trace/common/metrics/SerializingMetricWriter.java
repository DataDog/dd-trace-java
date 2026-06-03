package datadog.trace.common.metrics;

import static datadog.trace.bootstrap.instrumentation.api.UTF8BytesString.EMPTY;
import static java.nio.charset.StandardCharsets.ISO_8859_1;

import datadog.communication.serialization.GrowableBuffer;
import datadog.communication.serialization.WritableFormatter;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.metrics.api.Histogram;
import datadog.trace.api.ProcessTags;
import datadog.trace.api.WellKnownTags;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.GitInfoProvider;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.Function;

public final class SerializingMetricWriter implements MetricWriter {

  private static final byte[] SEQUENCE = "Sequence".getBytes(ISO_8859_1);
  private static final byte[] RUNTIME_ID = "RuntimeID".getBytes(ISO_8859_1);
  private static final byte[] HOSTNAME = "Hostname".getBytes(ISO_8859_1);
  private static final byte[] NAME = "Name".getBytes(ISO_8859_1);
  private static final byte[] ENV = "Env".getBytes(ISO_8859_1);
  private static final byte[] SERVICE = "Service".getBytes(ISO_8859_1);
  private static final byte[] RESOURCE = "Resource".getBytes(ISO_8859_1);
  private static final byte[] VERSION = "Version".getBytes(ISO_8859_1);
  private static final byte[] HITS = "Hits".getBytes(ISO_8859_1);
  private static final byte[] ERRORS = "Errors".getBytes(ISO_8859_1);
  private static final byte[] TOP_LEVEL_HITS = "TopLevelHits".getBytes(ISO_8859_1);
  private static final byte[] DURATION = "Duration".getBytes(ISO_8859_1);
  private static final byte[] TYPE = "Type".getBytes(ISO_8859_1);
  private static final byte[] HTTP_STATUS_CODE = "HTTPStatusCode".getBytes(ISO_8859_1);
  private static final byte[] SYNTHETICS = "Synthetics".getBytes(ISO_8859_1);
  private static final byte[] START = "Start".getBytes(ISO_8859_1);
  private static final byte[] STATS = "Stats".getBytes(ISO_8859_1);
  private static final byte[] OK_SUMMARY = "OkSummary".getBytes(ISO_8859_1);
  private static final byte[] ERROR_SUMMARY = "ErrorSummary".getBytes(ISO_8859_1);
  private static final byte[] PROCESS_TAGS = "ProcessTags".getBytes(ISO_8859_1);
  private static final byte[] IS_TRACE_ROOT = "IsTraceRoot".getBytes(ISO_8859_1);
  private static final byte[] SPAN_KIND = "SpanKind".getBytes(ISO_8859_1);
  private static final byte[] PEER_TAGS = "PeerTags".getBytes(ISO_8859_1);
  private static final byte[] ADDITIONAL_METRIC_TAGS = "AdditionalMetricTags".getBytes(ISO_8859_1);
  private static final byte[] HTTP_METHOD = "HTTPMethod".getBytes(ISO_8859_1);
  private static final byte[] HTTP_ENDPOINT = "HTTPEndpoint".getBytes(ISO_8859_1);
  private static final byte[] GRPC_STATUS_CODE = "GRPCStatusCode".getBytes(ISO_8859_1);
  private static final byte[] SERVICE_SOURCE = "srv_src".getBytes(ISO_8859_1);
  private static final byte[] GIT_COMMIT_SHA = "GitCommitSha".getBytes(ISO_8859_1);

  // Constant declared here for compile-time folding
  public static final int TRISTATE_TRUE = TriState.TRUE.serialValue;
  public static final int TRISTATE_FALSE = TriState.FALSE.serialValue;

  private static final Function<GitInfo, UTF8BytesString> SHA_COMMIT_GETTER =
      gitInfo ->
          gitInfo.getCommit() != null && gitInfo.getCommit().getSha() != null
              ? UTF8BytesString.create(gitInfo.getCommit().getSha())
              : EMPTY;

  private final WellKnownTags wellKnownTags;
  private final WritableFormatter writer;
  private final Sink sink;
  private final GrowableBuffer buffer;
  private final DDCache<GitInfo, UTF8BytesString> gitInfoCache =
      DDCaches.newFixedSizeWeakKeyCache(4);
  private long sequence = 0;
  private final GitInfoProvider gitInfoProvider;
  // Not final/eager: Histogram.newHistogram() requires the Histograms factory to be
  // registered first. SerializingMetricWriter is constructed during tracer startup before that
  // registration completes, so eager init would throw. Lazy init on first add() call is safe
  // because add() only runs on the aggregator thread, which starts after factory registration.
  // The single-writer invariant also means no synchronization is needed on this field.
  private byte[] emptyHistogramBytesCache;

  public SerializingMetricWriter(WellKnownTags wellKnownTags, Sink sink) {
    this(wellKnownTags, sink, 512 * 1024);
  }

  public SerializingMetricWriter(WellKnownTags wellKnownTags, Sink sink, int initialCapacity) {
    this(wellKnownTags, sink, initialCapacity, GitInfoProvider.INSTANCE);
  }

  public SerializingMetricWriter(
      WellKnownTags wellKnownTags,
      Sink sink,
      int initialCapacity,
      final GitInfoProvider gitInfoProvider) {
    this.wellKnownTags = wellKnownTags;
    this.buffer = new GrowableBuffer(initialCapacity);
    this.writer = new MsgPackWriter(buffer);
    this.sink = sink;
    this.gitInfoProvider = gitInfoProvider;
  }

  @Override
  public void startBucket(int metricCount, long start, long duration) {
    final UTF8BytesString processTags = ProcessTags.getTagsForSerialization();
    final boolean writeProcessTags = processTags != null;
    // the gitinfo can be rebuild in time and initialized after this class is created. So that a
    // cacheable is needed
    final UTF8BytesString gitSha =
        gitInfoCache.computeIfAbsent(gitInfoProvider.getGitInfo(), SHA_COMMIT_GETTER);
    final boolean writeGitCommitSha = gitSha != EMPTY;
    writer.startMap(7 + (writeProcessTags ? 1 : 0) + (writeGitCommitSha ? 1 : 0));

    writer.writeUTF8(RUNTIME_ID);
    writer.writeUTF8(wellKnownTags.getRuntimeId());

    writer.writeUTF8(SEQUENCE);
    writer.writeLong(sequence++);

    writer.writeUTF8(HOSTNAME);
    writer.writeUTF8(wellKnownTags.getHostname());

    writer.writeUTF8(SERVICE);
    writer.writeUTF8(wellKnownTags.getService());

    writer.writeUTF8(ENV);
    writer.writeUTF8(wellKnownTags.getEnv());

    writer.writeUTF8(VERSION);
    writer.writeUTF8(wellKnownTags.getVersion());

    if (writeProcessTags) {
      writer.writeUTF8(PROCESS_TAGS);
      writer.writeUTF8(processTags);
    }

    if (writeGitCommitSha) {
      writer.writeUTF8(GIT_COMMIT_SHA);
      writer.writeUTF8(gitSha);
    }

    writer.writeUTF8(STATS);

    writer.startArray(1);

    writer.startMap(3);

    writer.writeUTF8(START);
    writer.writeLong(start);

    writer.writeUTF8(DURATION);
    writer.writeLong(duration);

    writer.writeUTF8(STATS);
    writer.startArray(metricCount);
  }

  @Override
  public void add(AggregateEntry entry) {
    // Dynamic map size based on optional fields; AggregateEntry encapsulates the EMPTY-as-absent
    // sentinel via its hasFoo() predicates so the serializer doesn't depend on the storage choice.
    final boolean hasHttpMethod = entry.hasHttpMethod();
    final boolean hasHttpEndpoint = entry.hasHttpEndpoint();
    final boolean hasServiceSource = entry.hasServiceSource();
    final boolean hasGrpcStatusCode = entry.hasGrpcStatusCode();
    final UTF8BytesString[] additionalTags = entry.getAdditionalTags();
    final boolean hasAdditionalTags = additionalTags.length > 0;
    final int mapSize =
        15
            + (hasServiceSource ? 1 : 0)
            + (hasHttpMethod ? 1 : 0)
            + (hasHttpEndpoint ? 1 : 0)
            + (hasGrpcStatusCode ? 1 : 0)
            + (hasAdditionalTags ? 1 : 0);

    writer.startMap(mapSize);

    writer.writeUTF8(NAME);
    writer.writeUTF8(entry.getOperationName());

    writer.writeUTF8(SERVICE);
    writer.writeUTF8(entry.getService());

    writer.writeUTF8(RESOURCE);
    writer.writeUTF8(entry.getResource());

    writer.writeUTF8(TYPE);
    writer.writeUTF8(entry.getType());

    writer.writeUTF8(HTTP_STATUS_CODE);
    writer.writeInt(entry.getHttpStatusCode());

    writer.writeUTF8(SYNTHETICS);
    writer.writeBoolean(entry.isSynthetics());

    writer.writeUTF8(IS_TRACE_ROOT);
    writer.writeInt(entry.isTraceRoot() ? TRISTATE_TRUE : TRISTATE_FALSE);

    writer.writeUTF8(SPAN_KIND);
    writer.writeUTF8(entry.getSpanKind());

    writer.writeUTF8(PEER_TAGS);
    final List<UTF8BytesString> peerTags = entry.getPeerTags();
    writer.startArray(peerTags.size());

    for (UTF8BytesString peerTag : peerTags) {
      writer.writeUTF8(peerTag);
    }

    // Emit AdditionalMetricTags as repeated string of pre-built "key:value" UTF8BytesStrings, in
    // schema (alphabetical-by-key) order. Skip null slots (tags the span didn't set). The whole
    // field is omitted when no non-null slots exist so customers who don't configure additional
    // metric tags pay zero payload overhead.
    if (hasAdditionalTags) {
      writer.writeUTF8(ADDITIONAL_METRIC_TAGS);
      writer.startArray(additionalTags.length);
      for (UTF8BytesString slot : additionalTags) {
        writer.writeUTF8(slot);
      }
    }

    if (hasServiceSource) {
      writer.writeUTF8(SERVICE_SOURCE);
      writer.writeUTF8(entry.getServiceSource());
    }
    // Only include HTTPMethod if present
    if (hasHttpMethod) {
      writer.writeUTF8(HTTP_METHOD);
      writer.writeUTF8(entry.getHttpMethod());
    }

    // Only include HTTPEndpoint if present
    if (hasHttpEndpoint) {
      writer.writeUTF8(HTTP_ENDPOINT);
      writer.writeUTF8(entry.getHttpEndpoint());
    }

    // Only include GRPCStatusCode if present (rpc-type spans)
    if (hasGrpcStatusCode) {
      writer.writeUTF8(GRPC_STATUS_CODE);
      writer.writeUTF8(entry.getGrpcStatusCode());
    }

    writer.writeUTF8(HITS);
    writer.writeInt(entry.getHitCount());

    writer.writeUTF8(ERRORS);
    writer.writeInt(entry.getErrorCount());

    writer.writeUTF8(TOP_LEVEL_HITS);
    writer.writeInt(entry.getTopLevelCount());

    writer.writeUTF8(DURATION);
    writer.writeLong(entry.getDuration());

    writer.writeUTF8(OK_SUMMARY);
    writer.writeBinary(entry.getOkLatencies().serialize());

    writer.writeUTF8(ERROR_SUMMARY);
    final datadog.metrics.api.Histogram errorLatencies = entry.getErrorLatencies();
    if (errorLatencies != null) {
      writer.writeBinary(errorLatencies.serialize());
    } else {
      // Entry never saw an error; emit a cached empty-histogram payload so the wire format is
      // unchanged without allocating a histogram per entry.
      writer.writeBinary(emptyErrorHistogramBytes());
    }
  }

  /**
   * Returns the cached serialized form of an empty histogram. Computed lazily on first call so the
   * {@link datadog.metrics.api.Histograms} factory has been registered (by the producer-side tracer
   * startup or test setup) before we sample its output.
   */
  private byte[] emptyErrorHistogramBytes() {
    byte[] cached = emptyHistogramBytesCache;
    if (cached == null) {
      ByteBuffer buf = Histogram.newHistogram().serialize();
      cached = new byte[buf.remaining()];
      buf.get(cached);
      emptyHistogramBytesCache = cached;
    }
    return cached;
  }

  @Override
  public void finishBucket() {
    buffer.mark();
    sink.accept(buffer.messageCount(), buffer.slice());
    buffer.reset();
  }

  @Override
  public void reset() {
    buffer.reset();
  }
}
