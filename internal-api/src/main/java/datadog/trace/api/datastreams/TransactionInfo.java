package datadog.trace.api.datastreams;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class TransactionInfo implements InboxItem {
  private static final int MAX_ID_SIZE = 256;
  private static final Map<String, Integer> CACHE = new ConcurrentHashMap<>();
  private static volatile byte[] CACHE_BYTES = new byte[0];
  private static final AtomicInteger ID_COUNTER = new AtomicInteger(1);

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

  public long getTimestamp() {
    return timestamp;
  }

  public int getCheckpointId() {
    return checkpointId;
  }

  private int generateCheckpointId(String checkpoint) {
    int id = ID_COUNTER.getAndIncrement();

    // update cache bytes
    byte[] checkpointBytes = checkpoint.getBytes();
    byte[] bytesToAdd = new byte[checkpointBytes.length + 2];
    bytesToAdd[0] = (byte) id;
    bytesToAdd[1] = (byte) checkpointBytes.length;
    System.arraycopy(checkpointBytes, 0, bytesToAdd, 2, checkpointBytes.length);
    appendCacheBytes(bytesToAdd);

    return id;
  }

  private static synchronized void appendCacheBytes(byte[] bytes) {
    byte[] newCacheBytes = new byte[CACHE_BYTES.length + bytes.length];
    System.arraycopy(CACHE_BYTES, 0, newCacheBytes, 0, CACHE_BYTES.length);
    System.arraycopy(bytes, 0, newCacheBytes, CACHE_BYTES.length, bytes.length);
    CACHE_BYTES = newCacheBytes;
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
  static synchronized void resetCache() {
    CACHE.clear();
    CACHE_BYTES = new byte[0];
    ID_COUNTER.set(1);
  }

  public static byte[] getCheckpointIdCacheBytes() {
    return CACHE_BYTES.clone();
  }
}
