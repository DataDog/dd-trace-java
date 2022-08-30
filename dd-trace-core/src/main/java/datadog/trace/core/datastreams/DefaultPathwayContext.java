package datadog.trace.core.datastreams;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.datadoghq.sketch.ddsketch.encoding.ByteArrayInput;
import com.datadoghq.sketch.ddsketch.encoding.GrowingByteArrayOutput;
import com.datadoghq.sketch.ddsketch.encoding.VarEncodingHelper;
import datadog.trace.api.Config;
import datadog.trace.api.WellKnownTags;
import datadog.trace.api.function.Consumer;
import datadog.trace.api.time.TimeSource;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.PathwayContext;
import datadog.trace.bootstrap.instrumentation.api.StatsPoint;
import datadog.trace.core.Base64Decoder;
import datadog.trace.core.Base64Encoder;
import datadog.trace.util.FNV64Hash;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultPathwayContext implements PathwayContext {
  private static final Logger log = LoggerFactory.getLogger(DefaultPathwayContext.class);
  private final Lock lock = new ReentrantLock();
  private final WellKnownTags wellKnownTags;
  private final TimeSource timeSource;
  private final GrowingByteArrayOutput outputBuffer =
      GrowingByteArrayOutput.withInitialCapacity(20);

  // pathwayStartNanos is nanoseconds since epoch
  // Nano ticks is necessary because time differences should use a monotonically increasing clock
  // ticks is not comparable across JVMs
  private long pathwayStartNanos;
  private long pathwayStartNanoTicks;
  private long edgeStartNanoTicks;
  private long hash;
  private boolean started;

  private static final Set<String> hashableTagKeys =
      new HashSet<String>(
          Arrays.asList(
              TagsProcessor.GROUP_TAG,
              TagsProcessor.TYPE_TAG,
              TagsProcessor.TOPIC_TAG,
              TagsProcessor.EXCHANGE_TAG));

  public DefaultPathwayContext(TimeSource timeSource, WellKnownTags wellKnownTags) {
    this.timeSource = timeSource;
    this.wellKnownTags = wellKnownTags;
  }

  private DefaultPathwayContext(
      TimeSource timeSource,
      WellKnownTags wellKnownTags,
      long pathwayStartNanos,
      long pathwayStartNanoTicks,
      long edgeStartNanoTicks,
      long hash) {
    this.timeSource = timeSource;
    this.wellKnownTags = wellKnownTags;
    this.pathwayStartNanos = pathwayStartNanos;
    this.pathwayStartNanoTicks = pathwayStartNanoTicks;
    this.edgeStartNanoTicks = edgeStartNanoTicks;
    this.hash = hash;
    this.started = true;
  }

  @Override
  public boolean isStarted() {
    return started;
  }

  @Override
  public void setCheckpoint(
      LinkedHashMap<String, String> sortedTags, Consumer<StatsPoint> pointConsumer) {
    long startNanos = timeSource.getCurrentTimeNanos();
    long nanoTicks = timeSource.getNanoTicks();
    lock.lock();
    try {
      // So far, each tag key has only one tag value, so we're initializing the capacity to match
      // the number of tag keys for now. We should revisit this later if it's no longer the case.
      List<String> allTags = new ArrayList<>(sortedTags.size());
      PathwayHashBuilder pathwayHashBuilder = new PathwayHashBuilder(wellKnownTags);

      if (!started) {
        pathwayStartNanos = startNanos;
        pathwayStartNanoTicks = nanoTicks;
        edgeStartNanoTicks = nanoTicks;
        hash = 0;
        started = true;
        log.debug("Started {}", this);
      }

      for (Map.Entry<String, String> entry : sortedTags.entrySet()) {
        String tag = TagsProcessor.createTag(entry.getKey(), entry.getValue());
        if (hashableTagKeys.contains(entry.getKey())) {
          pathwayHashBuilder.addTag(tag);
        }
        allTags.add(tag);
      }

      long newHash = generatePathwayHash(pathwayHashBuilder, hash);

      long pathwayLatencyNano = nanoTicks - pathwayStartNanoTicks;
      long edgeLatencyNano = nanoTicks - edgeStartNanoTicks;

      StatsPoint point =
          new StatsPoint(
              allTags,
              newHash,
              hash,
              timeSource.getCurrentTimeNanos(),
              pathwayLatencyNano,
              edgeLatencyNano);
      edgeStartNanoTicks = nanoTicks;
      hash = newHash;

      pointConsumer.accept(point);
      log.debug("Checkpoint set {}", this);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public byte[] encode() throws IOException {
    lock.lock();
    try {
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
      return outputBuffer.trimmedCopy();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public String strEncode() throws IOException {
    byte[] bytes = encode();
    if (bytes == null) {
      return null;
    }
    return new String(Base64Encoder.INSTANCE.encode(bytes), UTF_8);
  }

  @Override
  public String toString() {
    lock.lock();
    try {
      if (started) {
        return "PathwayContext[ Hash "
            + toUnsignedString(hash)
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
    } finally {
      lock.unlock();
    }
  }

  // TODO Can be removed when Java7 support is removed
  private static String toUnsignedString(long l) {
    if (l >= 0) {
      return Long.toString(l);
    }

    // shift left once and divide by 5 results in an unsigned divide by 10
    long quot = (l >>> 1) / 5;
    long rem = l - quot * 10;
    return Long.toString(quot) + rem;
  }

  private static class PathwayContextExtractor implements AgentPropagation.KeyClassifier {
    private final TimeSource timeSource;
    private final WellKnownTags wellKnownTags;
    private DefaultPathwayContext extractedContext;

    PathwayContextExtractor(TimeSource timeSource, WellKnownTags wellKnownTags) {
      this.timeSource = timeSource;
      this.wellKnownTags = wellKnownTags;
    }

    @Override
    public boolean accept(String key, String value) {
      if (PathwayContext.PROPAGATION_KEY_BASE64.equalsIgnoreCase(key)) {
        try {
          extractedContext = strDecode(timeSource, wellKnownTags, value);
        } catch (IOException e) {
          return false;
        }
      }
      return true;
    }
  }

  private static class BinaryPathwayContextExtractor
      implements AgentPropagation.BinaryKeyClassifier {
    private final TimeSource timeSource;
    private final WellKnownTags wellKnownTags;
    private DefaultPathwayContext extractedContext;

    BinaryPathwayContextExtractor(TimeSource timeSource, WellKnownTags wellKnownTags) {
      this.timeSource = timeSource;
      this.wellKnownTags = wellKnownTags;
    }

    @Override
    public boolean accept(String key, byte[] value) {
      if (PathwayContext.PROPAGATION_KEY.equalsIgnoreCase(key)) {
        try {
          extractedContext = decode(timeSource, wellKnownTags, value);
        } catch (IOException e) {
          return false;
        }
      }
      return true;
    }
  }

  public static <C> DefaultPathwayContext extract(
      C carrier,
      AgentPropagation.ContextVisitor<C> getter,
      TimeSource timeSource,
      WellKnownTags wellKnownTags) {
    PathwayContextExtractor pathwayContextExtractor =
        new PathwayContextExtractor(timeSource, wellKnownTags);
    getter.forEachKey(carrier, pathwayContextExtractor);
    if (pathwayContextExtractor.extractedContext == null) {
      log.debug("No context extracted");
    } else {
      log.debug("Extracted context: {} ", pathwayContextExtractor.extractedContext);
    }
    return pathwayContextExtractor.extractedContext;
  }

  public static <C> DefaultPathwayContext extractBinary(
      C carrier,
      AgentPropagation.BinaryContextVisitor<C> getter,
      TimeSource timeSource,
      WellKnownTags wellKnownTags) {
    BinaryPathwayContextExtractor pathwayContextExtractor =
        new BinaryPathwayContextExtractor(timeSource, wellKnownTags);
    getter.forEachKey(carrier, pathwayContextExtractor);
    if (pathwayContextExtractor.extractedContext == null) {
      log.debug("No context extracted");
    } else {
      log.debug("Extracted context: {} ", pathwayContextExtractor.extractedContext);
    }
    return pathwayContextExtractor.extractedContext;
  }

  public static DefaultPathwayContext strDecode(
      TimeSource timeSource, WellKnownTags wellKnownTags, String data) throws IOException {
    byte[] byteValue = Base64Decoder.INSTANCE.decode(data.getBytes(UTF_8));
    return decode(timeSource, wellKnownTags, byteValue);
  }

  public static DefaultPathwayContext decode(
      TimeSource timeSource, WellKnownTags wellKnownTags, byte[] data) throws IOException {
    ByteArrayInput input = ByteArrayInput.wrap(data);

    long hash = input.readLongLE();

    long pathwayStartMillis = VarEncodingHelper.decodeSignedVarLong(input);
    long pathwayStartNanos = TimeUnit.MILLISECONDS.toNanos(pathwayStartMillis);

    // Convert the start time to the current JVM's nanoclock
    long nowNanos = timeSource.getCurrentTimeNanos();
    long nanosSinceStart = nowNanos - pathwayStartNanos;
    long nowNanoTicks = timeSource.getNanoTicks();
    long pathwayStartNanoTicks = nowNanoTicks - nanosSinceStart;

    long edgeStartMillis = VarEncodingHelper.decodeSignedVarLong(input);
    long edgeStartNanoTicks =
        pathwayStartNanoTicks + TimeUnit.MILLISECONDS.toNanos(edgeStartMillis - pathwayStartMillis);

    return new DefaultPathwayContext(
        timeSource,
        wellKnownTags,
        pathwayStartNanos,
        pathwayStartNanoTicks,
        edgeStartNanoTicks,
        hash);
  }

  private static class PathwayHashBuilder {
    private StringBuilder builder;

    public PathwayHashBuilder(WellKnownTags wellKnownTags) {
      builder = new StringBuilder();
      builder.append(wellKnownTags.getService());
      builder.append(wellKnownTags.getEnv());

      String primaryTag = Config.get().getPrimaryTag();
      if (primaryTag != null) {
        builder.append(primaryTag);
      }
    }

    public void addTag(String tag) {
      builder.append(tag);
    }

    public long generateHash() {
      return FNV64Hash.generateHash(builder.toString(), FNV64Hash.Version.v1);
    }
  }

  private long generateNodeHash(PathwayHashBuilder pathwayHashBuilder) {
    return pathwayHashBuilder.generateHash();
  }

  private long generatePathwayHash(PathwayHashBuilder pathwayHashBuilder, long parentHash) {
    long nodeHash = generateNodeHash(pathwayHashBuilder);

    lock.lock();
    try {
      outputBuffer.clear();
      outputBuffer.writeLongLE(nodeHash);
      outputBuffer.writeLongLE(parentHash);

      return FNV64Hash.generateHash(outputBuffer.backingArray(), 0, 16, FNV64Hash.Version.v1);
    } finally {
      lock.unlock();
    }
  }
}
