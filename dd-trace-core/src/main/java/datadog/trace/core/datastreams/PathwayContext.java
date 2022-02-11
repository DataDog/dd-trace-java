package datadog.trace.core.datastreams;

import com.datadoghq.sketch.ddsketch.encoding.ByteArrayInput;
import com.datadoghq.sketch.ddsketch.encoding.GrowingByteArrayOutput;
import com.datadoghq.sketch.ddsketch.encoding.VarEncodingHelper;
import datadog.trace.api.Config;
import datadog.trace.api.function.BiConsumer;
import datadog.trace.api.function.Consumer;
import datadog.trace.util.FNV64Hash;
import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class PathwayContext {
  public static String PROPAGATION_KEY = "dd-pathway-ctx";

  private final ReadWriteLock lock = new ReentrantReadWriteLock();
  private final Lock readLock = lock.readLock();
  private final Lock writeLock = lock.writeLock();

  // Nanotime is necessary because time differences should use a monotonically increasing clock
  // Milliseconds are kept because nanotime is not comparable across JVMs
  private final long pathwayStartMillis;
  private final long pathwayStart;
  private long edgeStart;
  private long hash;

  public PathwayContext(Consumer<StatsPoint> pointConsumer) {
    this(pointConsumer, System.currentTimeMillis(), System.nanoTime());
  }

  public PathwayContext(Consumer<StatsPoint> pointConsumer, long pathwayStartMillis, long pathwayStartNanoTime) {
    this(pathwayStartMillis, pathwayStartNanoTime, pathwayStartNanoTime, 0);

    setCheckpoint( "", pathwayStartNanoTime, pointConsumer);
  }

  public PathwayContext(
      long pathwayStartMillis, long pathwayStartNanoTime, long edgeStartNanoTime, long hash) {
    this.pathwayStartMillis = pathwayStartMillis;
    this.pathwayStart = pathwayStartNanoTime;
    this.edgeStart = edgeStartNanoTime;
    this.hash = hash;
  }

  public void setCheckpoint(String edge, Consumer<StatsPoint> pointConsumer) {
    setCheckpoint(edge, System.nanoTime(), pointConsumer);
  }

  public void setCheckpoint(String edge, long nanoTime, Consumer<StatsPoint> pointConsumer) {
    writeLock.lock();
    try {
      long nodeHash = generateNodeHash(Config.get().getServiceName(), edge);
      long newHash = generatePathwayHash(nodeHash, hash);

      long pathwayLatency = nanoTime - pathwayStart;
      long edgeLatency = nanoTime - edgeStart;

      StatsPoint point =
          new StatsPoint(
              edge,
              newHash,
              hash,
              System.currentTimeMillis(),
              pathwayLatency,
              edgeLatency);
      edgeStart = nanoTime;
      hash = newHash;

      pointConsumer.accept(point);
    } finally {
      writeLock.unlock();
    }
  }

  public byte[] encode() throws IOException {
    readLock.lock();
    try {
      GrowingByteArrayOutput output = GrowingByteArrayOutput.withInitialCapacity(20);

      output.writeLongLE(hash);
      VarEncodingHelper.encodeSignedVarLong(output, pathwayStartMillis);

      long edgeStartMillis =
          pathwayStartMillis + TimeUnit.NANOSECONDS.toMillis(edgeStart - pathwayStart);

      VarEncodingHelper.encodeSignedVarLong(output, edgeStartMillis);
      return output.trimmedCopy();
    } finally {
      readLock.unlock();
    }
  }

  public static PathwayContext decode(byte[] data) throws IOException {
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

    return new PathwayContext(pathwayStartMillis, pathwayStartNano, edgeStartNano, hash);
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
