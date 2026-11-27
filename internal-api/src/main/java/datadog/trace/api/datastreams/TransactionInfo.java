package datadog.trace.api.datastreams;

import datadog.trace.api.internal.VisibleForTesting;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class TransactionInfo implements InboxItem {
  static final int MAX_ID_SIZE = 255;
  static final int MAX_CHECKPOINT_NAME_SIZE = 255;
  static final int MAX_CHECKPOINTS = 254;
  static final int INVALID_CHECKPOINT_ID = 0;

  private static final byte[] EMPTY_BYTES = new byte[0];
  private static final Map<String, Integer> CACHE = new ConcurrentHashMap<>();
  private static volatile byte[] CACHE_BYTES = EMPTY_BYTES;
  private static int nextCheckpointId = 1;

  private final String id;
  private final long timestamp;
  private final int checkpointId;

  public TransactionInfo(String id, long timestamp, String checkpoint) {
    this.id = id;
    this.timestamp = timestamp;
    if (id == null || checkpoint == null) {
      this.checkpointId = INVALID_CHECKPOINT_ID;
      return;
    }
    Integer cachedId = CACHE.get(checkpoint);
    this.checkpointId = cachedId != null ? cachedId : getOrRegisterCheckpointId(checkpoint);
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

  public boolean isValid() {
    return checkpointId != INVALID_CHECKPOINT_ID;
  }

  private static synchronized int getOrRegisterCheckpointId(String checkpoint) {
    Integer existing = CACHE.get(checkpoint);
    if (existing != null) {
      return existing;
    }
    if (nextCheckpointId > MAX_CHECKPOINTS) {
      return INVALID_CHECKPOINT_ID;
    }

    int id = nextCheckpointId++;

    byte[] checkpointBytes = checkpoint.getBytes(StandardCharsets.UTF_8);
    int checkpointLen = truncateUtf8Length(checkpointBytes, MAX_CHECKPOINT_NAME_SIZE);
    int prevLen = CACHE_BYTES.length;
    byte[] newCacheBytes = new byte[prevLen + checkpointLen + 2];
    System.arraycopy(CACHE_BYTES, 0, newCacheBytes, 0, prevLen);
    newCacheBytes[prevLen] = (byte) id;
    newCacheBytes[prevLen + 1] = (byte) checkpointLen;
    System.arraycopy(checkpointBytes, 0, newCacheBytes, prevLen + 2, checkpointLen);
    CACHE_BYTES = newCacheBytes;

    CACHE.put(checkpoint, id);
    return id;
  }

  public byte[] getBytes() {
    if (!isValid()) {
      return EMPTY_BYTES;
    }
    byte[] idBytes = id.getBytes(StandardCharsets.UTF_8);

    int idLen = truncateUtf8Length(idBytes, MAX_ID_SIZE);
    ByteBuffer buffer = ByteBuffer.allocate(1 + Long.BYTES + 1 + idLen).order(ByteOrder.BIG_ENDIAN);

    buffer.put((byte) checkpointId);
    buffer.putLong(timestamp);
    buffer.put((byte) idLen);
    buffer.put(idBytes, 0, idLen);

    return buffer.array();
  }

  private static int truncateUtf8Length(byte[] bytes, int maxLength) {
    if (bytes.length <= maxLength) {
      return bytes.length;
    }
    int len = maxLength;
    // Back up past UTF-8 continuation bytes so truncation stays on a code-point boundary.
    while (len > 0 && (bytes[len] & 0xC0) == 0x80) {
      len--;
    }
    return len;
  }

  @VisibleForTesting
  static synchronized void resetCache() {
    CACHE.clear();
    CACHE_BYTES = EMPTY_BYTES;
    nextCheckpointId = 1;
  }

  public static byte[] getCheckpointIdCacheBytes() {
    // CACHE_BYTES are never modified outside of this class, so it's safe to pass the
    // value directly without cloning
    // no-dd-sa:java-best-practices/return-internal-array
    return CACHE_BYTES;
  }
}
