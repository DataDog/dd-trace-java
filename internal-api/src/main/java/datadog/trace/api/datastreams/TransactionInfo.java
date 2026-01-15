package datadog.trace.api.datastreams;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class TransactionInfo implements InboxItem {
  private static final int MAX_ID_SIZE = 256;
  private static final Map<String, Integer> CACHE = new HashMap<>();
  private static final AtomicInteger COUNTER = new AtomicInteger();
  private static byte[] CACHE_BYTES = new byte[0];

  private final String id;
  private final long timestamp;
  private final int checkpointId;

  public TransactionInfo(String id, long timestamp, String checkpoint) {
    this.id = id;
    this.timestamp = timestamp;
    this.checkpointId = resolveCheckpointId(checkpoint);
  }

  public String getId() {
    return id;
  }

  public Long getTimestamp() {
    return timestamp;
  }

  public Integer getCheckpointId() {
    return checkpointId;
  }

  private int resolveCheckpointId(String checkpoint) {
    // get the value and return, avoid locking
    int id = CACHE.getOrDefault(checkpoint, -1);
    if (id != -1) {
      return id;
    }

    // add a new value to cache
    synchronized (CACHE) {
      id = CACHE.getOrDefault(checkpoint, -1);
      if (id != -1) {
        return id;
      }

      id = CACHE.computeIfAbsent(checkpoint, k -> COUNTER.incrementAndGet());

      // update cache bytes
      byte[] checkpointBytes = checkpoint.getBytes();
      int idx = CACHE_BYTES.length;
      CACHE_BYTES = Arrays.copyOf(CACHE_BYTES, idx + 2 + checkpointBytes.length);
      CACHE_BYTES[idx] = (byte) id;
      CACHE_BYTES[idx + 1] = (byte) checkpointBytes.length;
      System.arraycopy(checkpointBytes, 0, CACHE_BYTES, idx + 2, checkpointBytes.length);

      return id;
    }
  }

  public byte[] getBytes() {
    byte[] idBytes = id.getBytes();

    // long ids will be truncated
    int idLen = Math.min(idBytes.length, MAX_ID_SIZE);
    ByteBuffer buffer = ByteBuffer.allocate(1 + Long.BYTES + 1 + idLen).order(ByteOrder.BIG_ENDIAN);

    buffer.put((byte) checkpointId);
    buffer.putLong(timestamp);
    buffer.put((byte) idLen);
    buffer.put(idBytes, 0, idLen);

    return buffer.array();
  }

  // @VisibleForTesting
  static void resetCache() {
    synchronized (CACHE) {
      CACHE.clear();
      COUNTER.set(0);
      CACHE_BYTES = new byte[0];
    }
  }

  public static byte[] getCheckpointIdCacheBytes() {
    return CACHE_BYTES.clone();
  }
}
