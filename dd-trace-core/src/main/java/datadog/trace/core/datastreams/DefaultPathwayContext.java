package datadog.trace.core.datastreams;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.datadoghq.sketch.ddsketch.encoding.ByteArrayInput;
import com.datadoghq.sketch.ddsketch.encoding.GrowingByteArrayOutput;
import com.datadoghq.sketch.ddsketch.encoding.VarEncodingHelper;
import datadog.context.propagation.CarrierVisitor;
import datadog.trace.api.datastreams.DataStreamsContext;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.api.datastreams.PathwayContext;
import datadog.trace.api.datastreams.StatsPoint;
import datadog.trace.api.time.TimeSource;
import datadog.trace.util.FNV64Hash;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultPathwayContext implements PathwayContext {
  private static final Logger log = LoggerFactory.getLogger(DefaultPathwayContext.class);
  private final TimeSource timeSource;
  private final String serviceNameOverride;
  private GrowingByteArrayOutput outputBuffer;

  // pathwayStartNanos is nanoseconds since epoch
  // Nano ticks is necessary because time differences should use a monotonically increasing clock
  // ticks is not comparable across JVMs
  private long pathwayStartNanos;
  private long pathwayStartNanoTicks;
  private long edgeStartNanoTicks;
  private StatsPoint savedStats;
  private long hash;
  private boolean started;
  // state variables used to memoize the pathway hash with
  // direction != current direction
  private long closestOppositeDirectionHash;
  private DataStreamsTags.Direction previousDirection;

  public DefaultPathwayContext(TimeSource timeSource, String serviceNameOverride) {
    this.timeSource = timeSource;
    this.serviceNameOverride = serviceNameOverride;
  }

  private DefaultPathwayContext(
      TimeSource timeSource,
      long pathwayStartNanos,
      long pathwayStartNanoTicks,
      long edgeStartNanoTicks,
      long hash,
      String serviceNameOverride) {
    this(timeSource, serviceNameOverride);
    this.pathwayStartNanos = pathwayStartNanos;
    this.pathwayStartNanoTicks = pathwayStartNanoTicks;
    this.edgeStartNanoTicks = edgeStartNanoTicks;
    this.hash = hash;
    this.closestOppositeDirectionHash = hash;
    this.started = true;
  }

  @Override
  public boolean isStarted() {
    return started;
  }

  @Override
  public long getHash() {
    return hash;
  }

  @Override
  @SuppressFBWarnings(
      value = "USO_UNSAFE_METHOD_SYNCHRONIZATION",
      justification =
          "Agent-internal pathway object; instances do not escape to application code that could synchronize on the monitor.")
  public synchronized void setCheckpoint(
      DataStreamsContext context, Consumer<StatsPoint> pointConsumer) {
    long startNanos = timeSource.getCurrentTimeNanos();
    long nanoTicks = timeSource.getNanoTicks();

    if (!started) {
      long defaultTimestamp = context.defaultTimestamp();
      if (defaultTimestamp == 0) {
        pathwayStartNanos = startNanos;
        pathwayStartNanoTicks = nanoTicks;
        edgeStartNanoTicks = nanoTicks;
      } else {
        pathwayStartNanos = MILLISECONDS.toNanos(defaultTimestamp);
        pathwayStartNanoTicks =
            nanoTicks - MILLISECONDS.toNanos(timeSource.getCurrentTimeMillis() - defaultTimestamp);
        edgeStartNanoTicks = pathwayStartNanoTicks;
      }

      hash = 0;
      started = true;
      log.debug("Started {}", this);
    }

    // generate node hash
    long nodeHash = context.tags().getHash();
    // loop protection - a node should not be chosen as parent
    // for a sequential node with the same direction, as this
    // will cause a `cardinality explosion` for hash / parentHash tag values
    DataStreamsTags.Direction direction = context.tags().getDirectionValue();
    if (direction == previousDirection && previousDirection != null) {
      hash = closestOppositeDirectionHash;
    } else {
      previousDirection = direction;
      closestOppositeDirectionHash = hash;
    }

    long newHash = generatePathwayHash(nodeHash, hash);
    long aggregationHash =
        FNV64Hash.continueHashLongBE(
            context.tags().getAggregationHash(), newHash, FNV64Hash.Version.v1);

    long pathwayLatencyNano = nanoTicks - pathwayStartNanoTicks;
    long edgeLatencyNano = nanoTicks - edgeStartNanoTicks;

    StatsPoint point =
        new StatsPoint(
            context.tags(),
            newHash,
            hash,
            aggregationHash,
            startNanos,
            pathwayLatencyNano,
            edgeLatencyNano,
            context.payloadSizeBytes(),
            serviceNameOverride);
    edgeStartNanoTicks = nanoTicks;
    hash = newHash;

    pointConsumer.accept(point);
  }

  @Override
  public void saveStats(StatsPoint point) {
    this.savedStats = point;
  }

  @Override
  public StatsPoint getSavedStats() {
    return this.savedStats;
  }

  @Override
  @SuppressFBWarnings(
      value = "USO_UNSAFE_METHOD_SYNCHRONIZATION",
      justification =
          "Agent-internal pathway object; instances do not escape to application code that could synchronize on the monitor.")
  public synchronized String encode() throws IOException {
    if (!started) {
      throw new IllegalStateException("Context must be started to encode");
    }

    long pathwayStartMillis = TimeUnit.NANOSECONDS.toMillis(pathwayStartNanos);
    long edgeStartMillis =
        pathwayStartMillis
            + TimeUnit.NANOSECONDS.toMillis(edgeStartNanoTicks - pathwayStartNanoTicks);

    String cached = PathwayContextCodecCache.getEncoded(hash, pathwayStartMillis, edgeStartMillis);
    if (cached != null) {
      return cached;
    }

    if (outputBuffer == null) {
      outputBuffer = GrowingByteArrayOutput.withInitialCapacity(20);
    }
    outputBuffer.clear();
    outputBuffer.writeLongLE(hash);
    VarEncodingHelper.encodeSignedVarLong(outputBuffer, pathwayStartMillis);
    VarEncodingHelper.encodeSignedVarLong(outputBuffer, edgeStartMillis);
    byte[] base64 = Base64.getEncoder().encode(outputBuffer.trimmedCopy());
    String result = new String(base64, ISO_8859_1);
    PathwayContextCodecCache.putEncoded(hash, pathwayStartMillis, edgeStartMillis, result);
    return result;
  }

  @Override
  @SuppressFBWarnings(
      value = "USO_UNSAFE_METHOD_SYNCHRONIZATION",
      justification =
          "Agent-internal pathway object; instances do not escape to application code that could synchronize on the monitor.")
  public synchronized String toString() {
    if (started) {
      return "PathwayContext[ Hash "
          + Long.toUnsignedString(hash)
          + ", Start: "
          + pathwayStartNanos
          + ", StartTicks: "
          + pathwayStartNanoTicks
          + ", Edge Start Ticks: "
          + edgeStartNanoTicks
          + ", objectHashcode:"
          + hashCode()
          + "]";
    } else {
      return "PathwayContext [Not Started]";
    }
  }

  private static class PathwayContextExtractor implements BiConsumer<String, String> {
    private final TimeSource timeSource;
    private final String serviceNameOverride;
    private DefaultPathwayContext extractedContext;

    PathwayContextExtractor(TimeSource timeSource, String serviceNameOverride) {
      this.timeSource = timeSource;
      this.serviceNameOverride = serviceNameOverride;
    }

    @Override
    public void accept(String key, String value) {
      if (value != null && PROPAGATION_KEY_BASE64.equalsIgnoreCase(key)) {
        try {
          extractedContext = decode(timeSource, serviceNameOverride, value);
        } catch (Throwable ignored) {
        }
      }
    }
  }

  static <C> DefaultPathwayContext extract(
      C carrier, CarrierVisitor<C> getter, TimeSource timeSource, String serviceNameOverride) {
    PathwayContextExtractor extractor =
        new PathwayContextExtractor(timeSource, serviceNameOverride);
    getter.forEachKeyValue(carrier, extractor);
    if (extractor.extractedContext == null) {
      log.debug("No context extracted");
    } else {
      log.debug("Extracted context: {} ", extractor.extractedContext);
    }
    return extractor.extractedContext;
  }

  protected static DefaultPathwayContext decode(
      TimeSource timeSource, String serviceNameOverride, String base64) throws IOException {
    long hash;
    long pathwayStartMillis;
    long edgeStartMillis;
    PathwayContextCodecCache.Decoded cached = PathwayContextCodecCache.getDecoded(base64);
    if (cached != null) {
      hash = cached.hash;
      pathwayStartMillis = cached.pathwayStartMillis;
      edgeStartMillis = cached.edgeStartMillis;
    } else {
      byte[] base64Bytes = base64.getBytes(UTF_8);
      byte[] bytes = Base64.getDecoder().decode(base64Bytes);
      ByteArrayInput input = ByteArrayInput.wrap(bytes);
      hash = input.readLongLE();
      pathwayStartMillis = VarEncodingHelper.decodeSignedVarLong(input);
      edgeStartMillis = VarEncodingHelper.decodeSignedVarLong(input);
      PathwayContextCodecCache.putDecoded(base64, hash, pathwayStartMillis, edgeStartMillis);
    }

    long pathwayStartNanos = TimeUnit.MILLISECONDS.toNanos(pathwayStartMillis);

    // Convert the start time to the current JVM's nano clock
    long nowNanos = timeSource.getCurrentTimeNanos();
    long nanosSinceStart = nowNanos - pathwayStartNanos;
    long nowNanoTicks = timeSource.getNanoTicks();
    long pathwayStartNanoTicks = nowNanoTicks - nanosSinceStart;

    long edgeStartNanoTicks =
        pathwayStartNanoTicks + TimeUnit.MILLISECONDS.toNanos(edgeStartMillis - pathwayStartMillis);

    return new DefaultPathwayContext(
        timeSource,
        pathwayStartNanos,
        pathwayStartNanoTicks,
        edgeStartNanoTicks,
        hash,
        serviceNameOverride);
  }

  private long generatePathwayHash(long nodeHash, long parentHash) {
    return FNV64Hash.generateHashLongsLE(nodeHash, parentHash, FNV64Hash.Version.v1);
  }
}
