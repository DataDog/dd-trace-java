package datadog.trace.api.datastreams;

import datadog.trace.api.Pair;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class TransactionInfo implements InboxItem {
  private static final DDCache<String, Integer> CACHE = DDCaches.newFixedSizeCache(64);
  private static final AtomicInteger COUNTER = new AtomicInteger();

  private final String id;
  private final long timestamp;
  private final int checkpointId;

  public TransactionInfo(String id, Long timestamp, String checkpoint) {
    this.id = id;
    this.timestamp = timestamp;
    this.checkpointId = CACHE.computeIfAbsent(checkpoint, k -> COUNTER.incrementAndGet());
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

  public byte[] getBytes() {
    byte[] idBytes = id.getBytes();
    byte[] result = new byte[idBytes.length + 10];
    // up to 1 byte for checkpoint id
    result[0] = (byte) (checkpointId);
    // 8 bytes for timestamp
    result[1] = (byte) (timestamp >> 56);
    result[2] = (byte) (timestamp >> 48);
    result[3] = (byte) (timestamp >> 40);
    result[4] = (byte) (timestamp >> 32);
    result[5] = (byte) (timestamp >> 24);
    result[6] = (byte) (timestamp >> 16);
    result[7] = (byte) (timestamp >> 8);
    result[8] = (byte) (timestamp);
    // id size, up to 256 bytes
    result[9] = (byte) (idBytes.length);
    // add id bytes
    System.arraycopy(idBytes, 0, result, 10, idBytes.length);
    return result;
  }

  static void resetCache() {
    CACHE.clear();
    COUNTER.set(0);
  }

  public static byte[] getCheckpointIdCacheBytes() {
    // get all values
    List<Pair<String, Integer>> pairs = new LinkedList<>();
    CACHE.visit(
        (key, value) -> {
          pairs.add(Pair.of(key, value));
        });

    // serialize
    byte[] result = new byte[1024];
    int index = 0;
    for (Pair<String, Integer> pair : pairs) {
      byte[] keyBytes = pair.getLeft().getBytes();
      // resize the buffer if needed
      if (result.length - index <= keyBytes.length + 2) {
        byte[] resized = new byte[result.length * 2];
        System.arraycopy(result, 0, resized, 0, result.length);
        result = resized;
      }

      result[index] = pair.getRight().byteValue();
      index++;
      result[index] = (byte) (keyBytes.length);
      index++;

      System.arraycopy(keyBytes, 0, result, index, keyBytes.length);
      index += keyBytes.length;
    }

    return Arrays.copyOf(result, index);
  }
}
