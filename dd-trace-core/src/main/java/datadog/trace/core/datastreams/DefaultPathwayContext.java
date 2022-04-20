package datadog.trace.core.datastreams;

import com.datadoghq.sketch.ddsketch.encoding.ByteArrayInput;
import com.datadoghq.sketch.ddsketch.encoding.GrowingByteArrayOutput;
import com.datadoghq.sketch.ddsketch.encoding.VarEncodingHelper;
import datadog.trace.api.Config;
import datadog.trace.api.function.Consumer;
import datadog.trace.api.time.TimeSource;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.PathwayContext;
import datadog.trace.bootstrap.instrumentation.api.StatsPoint;
import datadog.trace.util.FNV64Hash;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultPathwayContext implements PathwayContext {
  private static final Logger log = LoggerFactory.getLogger(DefaultPathwayContext.class);
  private static final String INITIALIZATION_TOPIC = "";
  private final Lock lock = new ReentrantLock();
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

  public DefaultPathwayContext(TimeSource timeSource) {
    this.timeSource = timeSource;
  }

  private DefaultPathwayContext(
      TimeSource timeSource,
      long pathwayStartNanos,
      long pathwayStartNanoTicks,
      long edgeStartNanoTicks,
      long hash) {
    this.timeSource = timeSource;
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
  public void start(Consumer<StatsPoint> pointConsumer) {
    setCheckpoint(null, null, INITIALIZATION_TOPIC, pointConsumer);
  }

  @Override
  public void setCheckpoint(
      String type, String group, String topic, Consumer<StatsPoint> pointConsumer) {

    long startNanos = timeSource.getCurrentTimeNanos();
    long nanoTicks = timeSource.getNanoTicks();

    lock.lock();
    try {
      if (INITIALIZATION_TOPIC.equals(topic) && started) {
        return;
      }

      String finalType;
      String finalGroup;
      String finalTopic;

      if (started) {
        finalType = type;
        finalGroup = group;
        finalTopic = topic;
      } else {
        // Ignore the edge if there are no parents (ie context hasn't started yet)
        // Initialize the context instead
        finalType = null;
        finalGroup = null;
        finalTopic = INITIALIZATION_TOPIC;
      }

      if (INITIALIZATION_TOPIC.equals(finalTopic)) {
        pathwayStartNanos = startNanos;
        pathwayStartNanoTicks = nanoTicks;
        edgeStartNanoTicks = nanoTicks;
        hash = 0;
        started = true;
        log.debug("Started {}", this);
      }

      long newHash = generatePathwayHash(finalTopic, hash);

      long pathwayLatencyNano = nanoTicks - pathwayStartNanoTicks;
      long edgeLatencyNano = nanoTicks - edgeStartNanoTicks;

      StatsPoint point =
          new StatsPoint(
              finalType,
              finalGroup,
              finalTopic,
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

  private static class PathwayContextExtractor implements AgentPropagation.BinaryKeyClassifier {
    private final TimeSource timeSource;
    private DefaultPathwayContext extractedContext;

    PathwayContextExtractor(TimeSource timeSource) {
      this.timeSource = timeSource;
    }

    @Override
    public boolean accept(String key, byte[] value) {
      if (PathwayContext.PROPAGATION_KEY.equalsIgnoreCase(key)) {
        try {
          extractedContext = decode(timeSource, value);
        } catch (IOException e) {
          return false;
        }
      }
      return true;
    }
  }

  public static <C> DefaultPathwayContext extract(
      C carrier, AgentPropagation.BinaryContextVisitor<C> getter, TimeSource timeSource) {
    PathwayContextExtractor pathwayContextExtractor = new PathwayContextExtractor(timeSource);
    getter.forEachKey(carrier, pathwayContextExtractor);

    if (pathwayContextExtractor.extractedContext == null) {
      log.debug("No context extracted");
    } else {
      log.debug("Extracted context: {} ", pathwayContextExtractor.extractedContext);
    }

    return pathwayContextExtractor.extractedContext;
  }

  public static DefaultPathwayContext decode(TimeSource timeSource, byte[] data)
      throws IOException {
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
        timeSource, pathwayStartNanos, pathwayStartNanoTicks, edgeStartNanoTicks, hash);
  }

  private static long generateNodeHash(String serviceName, String edge) {
    return FNV64Hash.generateHash(serviceName + edge, FNV64Hash.Version.v1);
  }

  private long generatePathwayHash(String edgeName, long parentHash) {
    long nodeHash = generateNodeHash(Config.get().getServiceName(), edgeName);

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
