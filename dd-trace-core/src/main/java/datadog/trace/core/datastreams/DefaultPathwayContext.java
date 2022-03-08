package datadog.trace.core.datastreams;

import com.datadoghq.sketch.ddsketch.encoding.ByteArrayInput;
import com.datadoghq.sketch.ddsketch.encoding.GrowingByteArrayOutput;
import com.datadoghq.sketch.ddsketch.encoding.VarEncodingHelper;
import datadog.trace.api.Config;
import datadog.trace.api.function.Consumer;
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
  private final Lock lock = new ReentrantLock();

  // Nanotime is necessary because time differences should use a monotonically increasing clock
  // Milliseconds are kept because nanotime is not comparable across JVMs
  private long pathwayStartMillis;
  private long pathwayStart;
  private long edgeStart;
  private long hash;
  private boolean started;

  public DefaultPathwayContext() {}

  private DefaultPathwayContext(
      long pathwayStartMillis, long pathwayStartNanoTime, long edgeStartNanoTime, long hash) {
    this.pathwayStartMillis = pathwayStartMillis;
    this.pathwayStart = pathwayStartNanoTime;
    this.edgeStart = edgeStartNanoTime;
    this.hash = hash;
    this.started = true;
  }

  @Override
  public boolean isStarted() {
    return started;
  }

  @Override
  public void setCheckpoint(
      String type, String group, String topic, Consumer<StatsPoint> pointConsumer) {

    long startMillis = System.currentTimeMillis();
    long nanoTime = System.nanoTime();

    lock.lock();
    try {
      if (PathwayContext.INITIALIZATION_TOPIC.equals(topic)) {
        if (started) {
          return;
        }

        pathwayStartMillis = startMillis;
        pathwayStart = nanoTime;
        edgeStart = nanoTime;
        hash = 0;
        started = true;
        log.debug("Started {}", this);
      }

      long nodeHash = generateNodeHash(Config.get().getServiceName(), topic);
      long newHash = generatePathwayHash(nodeHash, hash);

      long pathwayLatency = nanoTime - pathwayStart;
      long edgeLatency = nanoTime - edgeStart;

      StatsPoint point =
          new StatsPoint(
              type,
              group,
              topic,
              newHash,
              hash,
              System.currentTimeMillis(),
              pathwayLatency,
              edgeLatency);
      edgeStart = nanoTime;
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
      GrowingByteArrayOutput output = GrowingByteArrayOutput.withInitialCapacity(20);

      output.writeLongLE(hash);
      VarEncodingHelper.encodeSignedVarLong(output, pathwayStartMillis);

      long edgeStartMillis =
          pathwayStartMillis + TimeUnit.NANOSECONDS.toMillis(edgeStart - pathwayStart);

      VarEncodingHelper.encodeSignedVarLong(output, edgeStartMillis);
      return output.trimmedCopy();
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
            + ", StartMillis: "
            + pathwayStartMillis
            + ", Start: "
            + pathwayStart
            + ", Edge Start: "
            + edgeStart
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
    if (l >= 0) return Long.toString(l);

    // shift left once and divide by 5 results in an unsigned divide by 10
    long quot = (l >>> 1) / 5;
    long rem = l - quot * 10;
    return Long.toString(quot) + rem;
  }

  private static class PathwayContextExtractor implements AgentPropagation.BinaryKeyClassifier {
    private DefaultPathwayContext extractedContext;

    @Override
    public boolean accept(String key, byte[] value) {
      if (PathwayContext.PROPAGATION_KEY.equalsIgnoreCase(key)) {
        try {
          extractedContext = decode(value);
          log.debug("Extracted pathway context");
        } catch (IOException e) {
          return false;
        }
      }
      return true;
    }
  }

  public static <C> DefaultPathwayContext extract(
      C carrier, AgentPropagation.BinaryContextVisitor<C> getter) {
    log.debug("Extracting pathway context");
    PathwayContextExtractor pathwayContextExtractor = new PathwayContextExtractor();
    getter.forEachKey(carrier, pathwayContextExtractor);

    log.debug("Extracted context: {} ", pathwayContextExtractor.extractedContext);

    return pathwayContextExtractor.extractedContext;
  }

  public static DefaultPathwayContext decode(byte[] data) throws IOException {
    ByteArrayInput input = ByteArrayInput.wrap(data);

    long hash = input.readLongLE();

    // Convert the millisecond start time to the current JVM's nanoclock
    long pathwayStartMillis = VarEncodingHelper.decodeSignedVarLong(input);
    long nowMillis = System.currentTimeMillis();
    long nowNano = System.nanoTime();

    long pathwayStartNano =
        nowNano - TimeUnit.MILLISECONDS.toMicros(nowMillis - pathwayStartMillis);

    long edgeStartMillis = VarEncodingHelper.decodeSignedVarLong(input);
    long edgeStartNano =
        pathwayStartNano + TimeUnit.MILLISECONDS.toMicros(edgeStartMillis - pathwayStartMillis);

    return new DefaultPathwayContext(pathwayStartMillis, pathwayStartNano, edgeStartNano, hash);
  }

  private static long generateNodeHash(String serviceName, String edge) {
    return FNV64Hash.generateHash(serviceName + edge, FNV64Hash.Version.v1);
  }

  private static long generatePathwayHash(long nodeHash, long parentHash) {
    // TODO determine whether it makes more sense to reuse this array
    // has concurrency implications
    GrowingByteArrayOutput output = GrowingByteArrayOutput.withInitialCapacity(16);

    output.writeLongLE(nodeHash);
    output.writeLongLE(parentHash);

    return FNV64Hash.generateHash(output.backingArray(), 0, 16, FNV64Hash.Version.v1);
  }
}
