package datadog.trace.api.datastreams;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.api.Pair;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransactionInfoTest {

  @Test
  void testCheckpointIdCacheSerializationMultiple() {
    TransactionInfo.resetCache();
    int controlSize = 10;
    // generate multiple transaction ids to trigger cache updates
    for (int i = 0; i < controlSize; i++) {
      new TransactionInfo("id " + i, i, "checkpoint " + i);
    }

    List<Pair<String, Integer>> items = new ArrayList<>();
    // get cache data
    byte[] data = TransactionInfo.getCheckpointIdCacheBytes();
    int offset = 0;
    while (offset < data.length) {
      int id = data[offset];
      offset++;

      int size = data[offset];
      offset++;

      String checkpoint = new String(data, offset, size, UTF_8);
      offset += size;

      items.add(Pair.of(checkpoint, id));
    }

    assertEquals(controlSize, items.size());
    for (Pair<String, Integer> item : items) {
      // checkpoint ids are generated sequentially starting from 1
      assertEquals("checkpoint " + (item.getRight() - 1), item.getLeft());
    }
  }

  @Test
  void testCheckpointIdCacheSerialization() {
    TransactionInfo.resetCache();
    new TransactionInfo("id", 1, "checkpoint");

    byte[] bytes = TransactionInfo.getCheckpointIdCacheBytes();

    assertEquals(12, bytes.length);
    assertArrayEquals(new byte[] {1, 10, 99, 104, 101, 99, 107, 112, 111, 105, 110, 116}, bytes);
  }

  @Test
  void testTransactionIdSerialization() {
    TransactionInfo.resetCache();
    TransactionInfo test = new TransactionInfo("id", 1, "checkpoint");

    byte[] bytes = test.getBytes();

    assertEquals(12, bytes.length);
    assertArrayEquals(new byte[] {1, 0, 0, 0, 0, 0, 0, 0, 1, 2, 105, 100}, bytes);
  }
}
