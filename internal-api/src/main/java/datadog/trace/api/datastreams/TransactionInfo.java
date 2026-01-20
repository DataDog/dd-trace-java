package datadog.trace.api.datastreams;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class TransactionInfo implements InboxItem {
  private static final int MAX_ID_SIZE = 256;
  private static final Map<String, Integer> CACHE = new ConcurrentHashMap<>();
  private static byte[] CACHE_BYTES = new byte[0];

  private final String id;
  private final long timestamp;
  private final int checkpointId;

  public TransactionInfo(String id, long timestamp, String checkpoint) {
    this.id = id;
    this.timestamp = timestamp;
    this.checkpointId = CACHE.computeIfAbsent(checkpoint, k -> generateCheckpointId(checkpoint));
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

  private int generateCheckpointId(String checkpoint) {
    int id = CACHE.size() + 1;

    // update cache bytes
    byte[] checkpointBytes = checkpoint.getBytes();
    int idx = CACHE_BYTES.length;
    CACHE_BYTES = Arrays.copyOf(CACHE_BYTES, idx + 2 + checkpointBytes.length);
    CACHE_BYTES[idx] = (byte) id;
    CACHE_BYTES[idx + 1] = (byte) checkpointBytes.length;
    System.arraycopy(checkpointBytes, 0, CACHE_BYTES, idx + 2, checkpointBytes.length);

    return id;
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
    CACHE.clear();
    CACHE_BYTES = new byte[0];
  }

  public static byte[] getCheckpointIdCacheBytes() {
    return CACHE_BYTES.clone();
  }
}
