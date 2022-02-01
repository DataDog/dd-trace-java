package datadog.trace.core.datastreams;

import com.datadoghq.sketch.ddsketch.encoding.ByteArrayInput;
import com.datadoghq.sketch.ddsketch.encoding.GrowingByteArrayOutput;
import com.datadoghq.sketch.ddsketch.encoding.VarEncodingHelper;
import datadog.trace.api.Config;
import datadog.trace.util.FNV64Hash;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class PathwayContext {
  public static String PROPAGATION_KEY = "dd-pathway-ctx";

  // Nanotime is necessary because time differences should use a monotonically increasing clock
  // Milliseconds are kept because nanotime is not comparable across JVMs
  private final long pathwayStartMillis;
  private final long pathwayStart;
  private long edgeStart;
  private long hash;

  public PathwayContext() {
    this(System.currentTimeMillis(), System.nanoTime());
  }

  public PathwayContext(long pathwayStartMillis, long pathwayStartNanoTime) {
    this(pathwayStartMillis, pathwayStartNanoTime, pathwayStartNanoTime, 0);

    setCheckpoint("", pathwayStartNanoTime);
  }

  public PathwayContext(long pathwayStartMillis, long pathwayStartNanoTime, long edgeStartNanoTime, long hash) {
    this.pathwayStartMillis = pathwayStartMillis;
    this.pathwayStart = pathwayStartNanoTime;
    this.edgeStart = edgeStartNanoTime;
    this.hash = hash;
  }

  public void setCheckpoint(String edge) {
    setCheckpoint(edge, System.nanoTime());
  }

  public void setCheckpoint(String edge, long nanoTime) {
    long nodeHash = generateNodeHash(Config.get().getServiceName(), edge);
    long newHash = generatePathwayHash(nodeHash, hash);

    long pathwayLatency = nanoTime - pathwayStart;
    long edgeLatency = nanoTime - edgeStart;

    // TODO submit everything
    edgeStart = nanoTime;
    hash = newHash;
  }

  public byte[] encode() throws IOException {
    GrowingByteArrayOutput output = GrowingByteArrayOutput.withInitialCapacity(20);

    output.writeLongLE(hash);
    VarEncodingHelper.encodeSignedVarLong(output, pathwayStartMillis);

    long edgeStartMillis = pathwayStartMillis + TimeUnit.NANOSECONDS.toMillis(edgeStart - pathwayStart);

    VarEncodingHelper.encodeSignedVarLong(output, edgeStartMillis);

    return output.trimmedCopy();
  }

  public static PathwayContext decode(byte[] data) throws IOException {
    ByteArrayInput input = ByteArrayInput.wrap(data);

    long hash = input.readLongLE();

    // Convert the millisecond start time to the current JVM's nanoclock
    long pathwayStartMillis = VarEncodingHelper.decodeSignedVarLong(input);
    long nowMillis = System.currentTimeMillis();
    long nowNano = System.nanoTime();

    long pathwayStartNano = nowNano - TimeUnit.MILLISECONDS.toMicros(nowMillis - pathwayStartMillis);

    long edgeStartMillis = VarEncodingHelper.decodeSignedVarLong(input);
    long edgeStartNano = pathwayStartNano + TimeUnit.MILLISECONDS.toMicros(edgeStartMillis - pathwayStartMillis);

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
