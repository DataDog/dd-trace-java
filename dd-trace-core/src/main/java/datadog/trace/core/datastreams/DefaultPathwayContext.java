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
import java.util.Arrays;
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
  // Non-null only in Data Streams lite mode
  private final PathwaySeenSet seenSet;
  private final GrowingByteArrayOutput outputBuffer =
      GrowingByteArrayOutput.withInitialCapacity(20);

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
    this(timeSource, serviceNameOverride, null);
  }

  DefaultPathwayContext(TimeSource timeSource, String serviceNameOverride, PathwaySeenSet seenSet) {
    this.timeSource = timeSource;
    this.serviceNameOverride = serviceNameOverride;
    this.seenSet = seenSet;
  }

  private DefaultPathwayContext(
      TimeSource timeSource,
      long pathwayStartNanos,
      long pathwayStartNanoTicks,
      long edgeStartNanoTicks,
      long hash,
      String serviceNameOverride,
      PathwaySeenSet seenSet) {
    this(timeSource, serviceNameOverride, seenSet);
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
        FNV64Hash.continueHash(
            context.tags().getAggregationHash(),
            DataStreamsTags.longToBytes(newHash),
            FNV64Hash.Version.v1);

    if (seenSet != null) {
      long parentHash = hash;
      edgeStartNanoTicks = nanoTicks;
      hash = newHash;
      // Lite measures nothing; the monitoring marks the pathway seen once the point is queued
      if (!seenSet.contains(aggregationHash)) {
        pointConsumer.accept(
            new StatsPoint(
                context.tags(),
                newHash,
                parentHash,
                aggregationHash,
                startNanos,
                0,
                0,
                0,
                serviceNameOverride));
      }
      return;
    }

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

    outputBuffer.clear();
    outputBuffer.writeLongLE(hash);

    long pathwayStartMillis = TimeUnit.NANOSECONDS.toMillis(pathwayStartNanos);
    VarEncodingHelper.encodeSignedVarLong(outputBuffer, pathwayStartMillis);

    long edgeStartMillis =
        pathwayStartMillis
            + TimeUnit.NANOSECONDS.toMillis(edgeStartNanoTicks - pathwayStartNanoTicks);

    VarEncodingHelper.encodeSignedVarLong(outputBuffer, edgeStartMillis);
    byte[] base64 = Base64.getEncoder().encode(outputBuffer.trimmedCopy());
    return new String(base64, ISO_8859_1);
  }

  /**
   * Encodes straight from the scratch buffer, without the intermediate copies of {@link #encode}.
   */
  @Override
  @SuppressFBWarnings(
      value = "USO_UNSAFE_METHOD_SYNCHRONIZATION",
      justification =
          "Agent-internal pathway object; instances do not escape to application code that could synchronize on the monitor.")
  public synchronized byte[] encodeBytes() throws IOException {
    if (!started) {
      throw new IllegalStateException("Context must be started to encode");
    }

    outputBuffer.clear();
    outputBuffer.writeLongLE(hash);
    long pathwayStartMillis = TimeUnit.NANOSECONDS.toMillis(pathwayStartNanos);
    VarEncodingHelper.encodeSignedVarLong(outputBuffer, pathwayStartMillis);
    long edgeStartMillis =
        pathwayStartMillis
            + TimeUnit.NANOSECONDS.toMillis(edgeStartNanoTicks - pathwayStartNanoTicks);
    VarEncodingHelper.encodeSignedVarLong(outputBuffer, edgeStartMillis);
    return base64Encode(outputBuffer.backingArray(), outputBuffer.numWrittenBytes());
  }

  private static final byte[] BASE64_ALPHABET =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".getBytes(ISO_8859_1);
  private static final byte[] BASE64_VALUES = new byte[128];

  static {
    Arrays.fill(BASE64_VALUES, (byte) -1);
    for (int i = 0; i < BASE64_ALPHABET.length; i++) {
      BASE64_VALUES[BASE64_ALPHABET[i]] = (byte) i;
    }
  }

  /** Standard base64 with padding, as {@code Base64.getEncoder()}. */
  static byte[] base64Encode(byte[] src, int length) {
    byte[] dst = new byte[4 * ((length + 2) / 3)];
    int si = 0;
    int di = 0;
    int full = length - length % 3;
    while (si < full) {
      int bits = (src[si++] & 0xff) << 16 | (src[si++] & 0xff) << 8 | (src[si++] & 0xff);
      dst[di++] = BASE64_ALPHABET[(bits >>> 18) & 63];
      dst[di++] = BASE64_ALPHABET[(bits >>> 12) & 63];
      dst[di++] = BASE64_ALPHABET[(bits >>> 6) & 63];
      dst[di++] = BASE64_ALPHABET[bits & 63];
    }
    int remaining = length - full;
    if (remaining == 1) {
      int b0 = src[si] & 0xff;
      dst[di++] = BASE64_ALPHABET[b0 >>> 2];
      dst[di++] = BASE64_ALPHABET[(b0 << 4) & 63];
      dst[di++] = '=';
      dst[di] = '=';
    } else if (remaining == 2) {
      int b0 = src[si] & 0xff;
      int b1 = src[si + 1] & 0xff;
      dst[di++] = BASE64_ALPHABET[b0 >>> 2];
      dst[di++] = BASE64_ALPHABET[((b0 << 4) | (b1 >>> 4)) & 63];
      dst[di++] = BASE64_ALPHABET[(b1 << 2) & 63];
      dst[di] = '=';
    }
    return dst;
  }

  /**
   * Decodes standard base64 with the rules of {@code Base64.getDecoder()} (padding optional, but
   * correct if present; nothing after it). Returns the number of bytes written to {@code dst}, or
   * -1 if the input is not valid base64. {@code dst} must hold {@code 3 * (length / 4 + 1)} bytes.
   */
  static int base64Decode(String src, byte[] dst) {
    int length = src.length();
    if (length == 1) {
      return -1;
    }
    int bits = 0;
    int shift = 18;
    int sp = 0;
    int dp = 0;
    while (sp < length) {
      char c = src.charAt(sp++);
      int value = c < 128 ? BASE64_VALUES[c] : -1;
      if (value < 0) {
        if (c != '=') {
          return -1;
        }
        if (shift == 18 || (shift == 6 && (sp == length || src.charAt(sp++) != '='))) {
          return -1;
        }
        break;
      }
      bits |= value << shift;
      shift -= 6;
      if (shift < 0) {
        dst[dp++] = (byte) (bits >> 16);
        dst[dp++] = (byte) (bits >> 8);
        dst[dp++] = (byte) bits;
        shift = 18;
        bits = 0;
      }
    }
    if (shift == 6) {
      dst[dp++] = (byte) (bits >> 16);
    } else if (shift == 0) {
      dst[dp++] = (byte) (bits >> 16);
      dst[dp++] = (byte) (bits >> 8);
    } else if (shift == 12) {
      return -1;
    }
    return sp < length ? -1 : dp;
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
    private final PathwaySeenSet seenSet;
    private PathwayContext extractedContext;

    PathwayContextExtractor(
        TimeSource timeSource, String serviceNameOverride, PathwaySeenSet seenSet) {
      this.timeSource = timeSource;
      this.serviceNameOverride = serviceNameOverride;
      this.seenSet = seenSet;
    }

    @Override
    public void accept(String key, String value) {
      if (value != null && PROPAGATION_KEY_BASE64.equalsIgnoreCase(key)) {
        if (UnsampledPathwayContext.ENCODED.equals(value)) {
          extractedContext = UnsampledPathwayContext.INSTANCE;
          return;
        }
        DefaultPathwayContext decoded =
            decodeOrNull(timeSource, serviceNameOverride, seenSet, value);
        if (decoded != null) {
          extractedContext = decoded;
        }
      }
    }
  }

  static <C> PathwayContext extract(
      C carrier, CarrierVisitor<C> getter, TimeSource timeSource, String serviceNameOverride) {
    return extract(carrier, getter, timeSource, serviceNameOverride, null);
  }

  static <C> PathwayContext extract(
      C carrier,
      CarrierVisitor<C> getter,
      TimeSource timeSource,
      String serviceNameOverride,
      PathwaySeenSet seenSet) {
    PathwayContextExtractor extractor =
        new PathwayContextExtractor(timeSource, serviceNameOverride, seenSet);
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
    return decode(timeSource, serviceNameOverride, null, base64);
  }

  static DefaultPathwayContext decode(
      TimeSource timeSource, String serviceNameOverride, PathwaySeenSet seenSet, String base64)
      throws IOException {
    byte[] base64Bytes = base64.getBytes(UTF_8);
    byte[] bytes = Base64.getDecoder().decode(base64Bytes);
    ByteArrayInput input = ByteArrayInput.wrap(bytes);

    long hash = input.readLongLE();

    long pathwayStartMillis = VarEncodingHelper.decodeSignedVarLong(input);
    long pathwayStartNanos = TimeUnit.MILLISECONDS.toNanos(pathwayStartMillis);

    // Convert the start time to the current JVM's nano clock
    long nowNanos = timeSource.getCurrentTimeNanos();
    long nanosSinceStart = nowNanos - pathwayStartNanos;
    long nowNanoTicks = timeSource.getNanoTicks();
    long pathwayStartNanoTicks = nowNanoTicks - nanosSinceStart;

    long edgeStartMillis = VarEncodingHelper.decodeSignedVarLong(input);
    long edgeStartNanoTicks =
        pathwayStartNanoTicks + TimeUnit.MILLISECONDS.toNanos(edgeStartMillis - pathwayStartMillis);

    return new DefaultPathwayContext(
        timeSource,
        pathwayStartNanos,
        pathwayStartNanoTicks,
        edgeStartNanoTicks,
        hash,
        serviceNameOverride,
        seenSet);
  }

  /**
   * Same result as {@link #decode} for valid input, but returns null instead of throwing on
   * malformed input, and parses without the intermediate byte arrays and stream.
   */
  static DefaultPathwayContext decodeOrNull(
      TimeSource timeSource, String serviceNameOverride, PathwaySeenSet seenSet, String base64) {
    byte[] bytes = new byte[3 * (base64.length() / 4 + 1)];
    int length = base64Decode(base64, bytes);
    if (length < 8) {
      return null;
    }
    long hash = 0;
    for (int i = 7; i >= 0; i--) {
      hash = (hash << 8) | (bytes[i] & 0xffL);
    }
    int pos = 8;
    long pathwayStartMillis = 0;
    int shift = 0;
    while (true) {
      if (pos >= length) {
        return null;
      }
      byte b = bytes[pos++];
      if (b >= 0 || shift == 56) {
        pathwayStartMillis |= ((long) b) << shift;
        break;
      }
      pathwayStartMillis |= (b & 127L) << shift;
      shift += 7;
    }
    long edgeStartMillis = 0;
    shift = 0;
    while (true) {
      if (pos >= length) {
        return null;
      }
      byte b = bytes[pos++];
      if (b >= 0 || shift == 56) {
        edgeStartMillis |= ((long) b) << shift;
        break;
      }
      edgeStartMillis |= (b & 127L) << shift;
      shift += 7;
    }
    pathwayStartMillis = (pathwayStartMillis >>> 1) ^ -(pathwayStartMillis & 1);
    edgeStartMillis = (edgeStartMillis >>> 1) ^ -(edgeStartMillis & 1);

    long pathwayStartNanos = TimeUnit.MILLISECONDS.toNanos(pathwayStartMillis);
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
        serviceNameOverride,
        seenSet);
  }

  private long generatePathwayHash(long nodeHash, long parentHash) {
    outputBuffer.clear();
    outputBuffer.writeLongLE(nodeHash);
    outputBuffer.writeLongLE(parentHash);
    return FNV64Hash.generateHash(outputBuffer.backingArray(), 0, 16, FNV64Hash.Version.v1);
  }
}
