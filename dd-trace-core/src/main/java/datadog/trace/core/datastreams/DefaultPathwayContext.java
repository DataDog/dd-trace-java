package datadog.trace.core.datastreams;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.datadoghq.sketch.ddsketch.encoding.ByteArrayInput;
import com.datadoghq.sketch.ddsketch.encoding.GrowingByteArrayOutput;
import com.datadoghq.sketch.ddsketch.encoding.VarEncodingHelper;
import datadog.context.propagation.CarrierVisitor;
import datadog.trace.api.Config;
import datadog.trace.api.ProcessTags;
import datadog.trace.api.WellKnownTags;
import datadog.trace.api.datastreams.DataStreamsContext;
import datadog.trace.api.datastreams.PathwayContext;
import datadog.trace.api.datastreams.StatsPoint;
import datadog.trace.api.time.TimeSource;
import datadog.trace.util.FNV64Hash;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultPathwayContext implements PathwayContext {
  private static final Logger log = LoggerFactory.getLogger(DefaultPathwayContext.class);
  private final Lock lock = new ReentrantLock();
  private final long hashOfKnownTags;
  private final TimeSource timeSource;
  private final String serviceNameOverride;
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
  private String previousDirection;

  private static final Set<String> hashableTagKeys =
      new HashSet<String>(
          Arrays.asList(
              TagsProcessor.GROUP_TAG,
              TagsProcessor.TYPE_TAG,
              TagsProcessor.DIRECTION_TAG,
              TagsProcessor.TOPIC_TAG,
              TagsProcessor.EXCHANGE_TAG));

  private static final Set<String> extraAggregationTagKeys =
      new HashSet<String>(
          Arrays.asList(
              TagsProcessor.DATASET_NAME_TAG,
              TagsProcessor.DATASET_NAMESPACE_TAG,
              TagsProcessor.MANUAL_TAG));

  public DefaultPathwayContext(
      TimeSource timeSource, long hashOfKnownTags, String serviceNameOverride) {
    this.timeSource = timeSource;
    this.hashOfKnownTags = hashOfKnownTags;
    this.serviceNameOverride = serviceNameOverride;
  }

  private DefaultPathwayContext(
      TimeSource timeSource,
      long hashOfKnownTags,
      long pathwayStartNanos,
      long pathwayStartNanoTicks,
      long edgeStartNanoTicks,
      long hash,
      String serviceNameOverride) {
    this(timeSource, hashOfKnownTags, serviceNameOverride);
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
  public void setCheckpoint(DataStreamsContext context, Consumer<StatsPoint> pointConsumer) {
    long startNanos = timeSource.getCurrentTimeNanos();
    long nanoTicks = timeSource.getNanoTicks();
    lock.lock();
    try {
      // So far, each tag key has only one tag value, so we're initializing the capacity to match
      // the number of tag keys for now. We should revisit this later if it's no longer the case.
      LinkedHashMap<String, String> sortedTags = context.sortedTags();
      List<String> allTags = new ArrayList<>(sortedTags.size());
      PathwayHashBuilder pathwayHashBuilder =
          new PathwayHashBuilder(hashOfKnownTags, serviceNameOverride);
      DataSetHashBuilder aggregationHashBuilder = new DataSetHashBuilder();

      if (!started) {
        long defaultTimestamp = context.defaultTimestamp();
        if (defaultTimestamp == 0) {
          pathwayStartNanos = startNanos;
          pathwayStartNanoTicks = nanoTicks;
          edgeStartNanoTicks = nanoTicks;
        } else {
          pathwayStartNanos = MILLISECONDS.toNanos(defaultTimestamp);
          pathwayStartNanoTicks =
              nanoTicks
                  - MILLISECONDS.toNanos(timeSource.getCurrentTimeMillis() - defaultTimestamp);
          edgeStartNanoTicks = pathwayStartNanoTicks;
        }

        hash = 0;
        started = true;
        log.debug("Started {}", this);
      }

      for (Map.Entry<String, String> entry : sortedTags.entrySet()) {
        String tag = TagsProcessor.createTag(entry.getKey(), entry.getValue());
        if (tag == null) {
          continue;
        }
        if (hashableTagKeys.contains(entry.getKey())) {
          pathwayHashBuilder.addTag(tag);
        }
        if (extraAggregationTagKeys.contains(entry.getKey())) {
          aggregationHashBuilder.addValue(tag);
        }
        allTags.add(tag);
      }

      long nodeHash = generateNodeHash(pathwayHashBuilder);
      // loop protection - a node should not be chosen as parent
      // for a sequential node with the same direction, as this
      // will cause a `cardinality explosion` for hash / parentHash tag values
      if (sortedTags.containsKey(TagsProcessor.DIRECTION_TAG)) {
        String direction = sortedTags.get(TagsProcessor.DIRECTION_TAG);
        if (direction.equals(previousDirection)) {
          hash = closestOppositeDirectionHash;
        } else {
          previousDirection = direction;
          closestOppositeDirectionHash = hash;
        }
      }

      long newHash = generatePathwayHash(nodeHash, hash);
      long aggregationHash = aggregationHashBuilder.addValue(String.valueOf(newHash));

      long pathwayLatencyNano = nanoTicks - pathwayStartNanoTicks;
      long edgeLatencyNano = nanoTicks - edgeStartNanoTicks;

      StatsPoint point =
          new StatsPoint(
              allTags,
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
      log.debug("Checkpoint set {}, hash source: {}", this, pathwayHashBuilder);
    } finally {
      lock.unlock();
    }
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
  public String encode() throws IOException {
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
      byte[] base64 = Base64.getEncoder().encode(outputBuffer.trimmedCopy());
      return new String(base64, ISO_8859_1);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public String toString() {
    lock.lock();
    try {
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
    } finally {
      lock.unlock();
    }
  }

  private static class PathwayContextExtractor implements BiConsumer<String, String> {
    private final TimeSource timeSource;
    private final long hashOfKnownTags;
    private final String serviceNameOverride;
    private DefaultPathwayContext extractedContext;

    PathwayContextExtractor(
        TimeSource timeSource, long hashOfKnownTags, String serviceNameOverride) {
      this.timeSource = timeSource;
      this.hashOfKnownTags = hashOfKnownTags;
      this.serviceNameOverride = serviceNameOverride;
    }

    @Override
    public void accept(String key, String value) {
      if (PROPAGATION_KEY_BASE64.equalsIgnoreCase(key)) {
        try {
          extractedContext = decode(timeSource, hashOfKnownTags, serviceNameOverride, value);
        } catch (IOException ignored) {
        }
      }
    }
  }

  static <C> DefaultPathwayContext extract(
      C carrier,
      CarrierVisitor<C> getter,
      TimeSource timeSource,
      long hashOfKnownTags,
      String serviceNameOverride) {
    PathwayContextExtractor pathwayContextExtractor =
        new PathwayContextExtractor(timeSource, hashOfKnownTags, serviceNameOverride);
    getter.forEachKeyValue(carrier, pathwayContextExtractor);
    if (pathwayContextExtractor.extractedContext == null) {
      log.debug("No context extracted");
    } else {
      log.debug("Extracted context: {} ", pathwayContextExtractor.extractedContext);
    }
    return pathwayContextExtractor.extractedContext;
  }

  private static DefaultPathwayContext decode(
      TimeSource timeSource, long hashOfKnownTags, String serviceNameOverride, String base64)
      throws IOException {
    byte[] base64Bytes = base64.getBytes(UTF_8);
    byte[] bytes = Base64.getDecoder().decode(base64Bytes);
    ByteArrayInput input = ByteArrayInput.wrap(bytes);

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
        hashOfKnownTags,
        pathwayStartNanos,
        pathwayStartNanoTicks,
        edgeStartNanoTicks,
        hash,
        serviceNameOverride);
  }

  static class DataSetHashBuilder {
    private long currentHash = 0L;

    public long addValue(String val) {
      currentHash = FNV64Hash.generateHash(currentHash + val, FNV64Hash.Version.v1);
      return currentHash;
    }
  }

  private static class PathwayHashBuilder {
    private long hash;

    public PathwayHashBuilder(long baseHash, String serviceNameOverride) {
      hash = baseHash;
      if (serviceNameOverride != null) {
        addTag(serviceNameOverride);
      }
    }

    public void addTag(String tag) {
      hash = FNV64Hash.continueHash(hash, tag, FNV64Hash.Version.v1);
    }

    public long getHash() {
      return hash;
    }
  }

  public static long getBaseHash(WellKnownTags wellKnownTags) {
    StringBuilder builder = new StringBuilder();
    builder.append(wellKnownTags.getService());
    builder.append(wellKnownTags.getEnv());

    String primaryTag = Config.get().getPrimaryTag();
    if (primaryTag != null) {
      builder.append(primaryTag);
    }
    CharSequence processTags = ProcessTags.getTagsForSerialization();
    if (processTags != null) {
      builder.append(processTags);
    }
    return FNV64Hash.generateHash(builder.toString(), FNV64Hash.Version.v1);
  }

  private long generateNodeHash(PathwayHashBuilder pathwayHashBuilder) {
    return pathwayHashBuilder.getHash();
  }

  private long generatePathwayHash(long nodeHash, long parentHash) {
    outputBuffer.clear();
    outputBuffer.writeLongLE(nodeHash);
    outputBuffer.writeLongLE(parentHash);
    return FNV64Hash.generateHash(outputBuffer.backingArray(), 0, 16, FNV64Hash.Version.v1);
  }
}
