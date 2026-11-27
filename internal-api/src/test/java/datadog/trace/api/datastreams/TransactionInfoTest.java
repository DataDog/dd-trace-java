package datadog.trace.api.datastreams;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.Pair;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class TransactionInfoTest {
  private static String repeatChar(char c, int count) {
    char[] chars = new char[count];
    Arrays.fill(chars, c);
    return new String(chars);
  }

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

  @Test
  void testTransactionIdTruncatedTo255BytesWithoutLengthOverflow() {
    TransactionInfo.resetCache();
    String id255 = repeatChar('a', 255);
    String id256 = repeatChar('b', 256);
    String id300 = repeatChar('c', 300);

    for (String id : Arrays.asList(id255, id256, id300)) {
      TransactionInfo info = new TransactionInfo(id, 42L, "checkpoint");
      byte[] bytes = info.getBytes();

      assertEquals(1 + Long.BYTES + 1 + 255, bytes.length);
      ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
      assertEquals(1, Byte.toUnsignedInt(buffer.get()));
      assertEquals(42L, buffer.getLong());
      int decodedLen = Byte.toUnsignedInt(buffer.get());
      assertEquals(255, decodedLen);
      byte[] decodedId = new byte[decodedLen];
      buffer.get(decodedId);
      assertEquals(id.substring(0, 255), new String(decodedId, UTF_8));
      assertFalse(buffer.hasRemaining());
    }
  }

  @TableTest({
    "Scenario                  | Prefix length | Character    | Expected suffix",
    "split 2-byte character    | 254           | \u00e9       | ''             ",
    "complete 2-byte character | 253           | \u00e9       | \u00e9         ",
    "3-byte split after byte 1 | 254           | \u20ac       | ''             ",
    "3-byte split after byte 2 | 253           | \u20ac       | ''             ",
    "complete 3-byte character | 252           | \u20ac       | \u20ac         ",
    "4-byte split after byte 1 | 254           | \uD83D\uDE00 | ''             ",
    "4-byte split after byte 2 | 253           | \uD83D\uDE00 | ''             ",
    "4-byte split after byte 3 | 252           | \uD83D\uDE00 | ''             ",
    "complete 4-byte character | 251           | \uD83D\uDE00 | \uD83D\uDE00   "
  })
  void testTruncationRespectsUtf8CodePointBoundaries(
      int prefixLength, String character, String expectedSuffix) {
    TransactionInfo.resetCache();
    String prefix = repeatChar('a', prefixLength);
    String input = prefix + character + "tail";
    String expected = prefix + expectedSuffix;
    int expectedLength = expected.getBytes(UTF_8).length;
    TransactionInfo info = new TransactionInfo(input, 1L, input);

    byte[] serialized = info.getBytes();
    int idOffset = 1 + Long.BYTES + 1;
    assertEquals(idOffset + expectedLength, serialized.length);
    assertEquals(expectedLength, Byte.toUnsignedInt(serialized[idOffset - 1]));
    assertEquals(expected, new String(serialized, idOffset, expectedLength, UTF_8));

    byte[] checkpoints = TransactionInfo.getCheckpointIdCacheBytes();
    assertEquals(2 + expectedLength, checkpoints.length);
    assertEquals(1, Byte.toUnsignedInt(checkpoints[0]));
    assertEquals(expectedLength, Byte.toUnsignedInt(checkpoints[1]));
    assertEquals(expected, new String(checkpoints, 2, expectedLength, UTF_8));
  }

  @Test
  void testLongCheckpointNameTruncatedTo255Bytes() {
    TransactionInfo.resetCache();
    String longCheckpoint = repeatChar('p', 300);
    String multibyteCheckpoint = repeatChar('q', 254) + "\u00e9";

    new TransactionInfo("tx1", 1L, longCheckpoint);
    new TransactionInfo("tx2", 2L, multibyteCheckpoint);
    new TransactionInfo("tx3", 3L, "after");

    byte[] data = TransactionInfo.getCheckpointIdCacheBytes();
    List<Pair<String, Integer>> decoded = new ArrayList<>();
    int offset = 0;
    while (offset < data.length) {
      int id = Byte.toUnsignedInt(data[offset++]);
      int size = Byte.toUnsignedInt(data[offset++]);
      decoded.add(Pair.of(new String(data, offset, size, UTF_8), id));
      offset += size;
    }

    assertEquals(3, decoded.size());
    assertEquals(Pair.of(repeatChar('p', 255), 1), decoded.get(0));
    assertEquals(Pair.of(repeatChar('q', 254), 2), decoded.get(1));
    assertEquals(Pair.of("after", 3), decoded.get(2));
  }

  @Test
  void testCheckpointRegistryCapsAt254AndDropsOverflowWithoutWrapping() {
    TransactionInfo.resetCache();
    for (int i = 1; i <= TransactionInfo.MAX_CHECKPOINTS; i++) {
      TransactionInfo info = new TransactionInfo("tx-" + i, i, "cp-" + i);
      assertTrue(info.isValid());
      assertEquals(i, info.getCheckpointId());
    }

    byte[] bytesBeforeOverflow = TransactionInfo.getCheckpointIdCacheBytes();

    TransactionInfo overflow = new TransactionInfo("tx-overflow", 999L, "cp-overflow");
    assertFalse(overflow.isValid());
    assertEquals(TransactionInfo.INVALID_CHECKPOINT_ID, overflow.getCheckpointId());
    assertEquals(0, overflow.getBytes().length);
    assertArrayEquals(bytesBeforeOverflow, TransactionInfo.getCheckpointIdCacheBytes());

    // Existing checkpoints continue to work after the registry is full.
    TransactionInfo reused = new TransactionInfo("tx-reused", 1000L, "cp-254");
    assertTrue(reused.isValid());
    assertEquals(254, reused.getCheckpointId());

    int offset = 0;
    int count = 0;
    while (offset < bytesBeforeOverflow.length) {
      int id = Byte.toUnsignedInt(bytesBeforeOverflow[offset++]);
      int size = Byte.toUnsignedInt(bytesBeforeOverflow[offset++]);
      String name = new String(bytesBeforeOverflow, offset, size, UTF_8);
      offset += size;
      count++;
      assertEquals(count, id);
      assertEquals("cp-" + count, name);
    }
    assertEquals(TransactionInfo.MAX_CHECKPOINTS, count);
  }

  @Test
  void testNullInputsProduceInvalidTransactionInfo() {
    TransactionInfo.resetCache();
    TransactionInfo nullId = new TransactionInfo(null, 1L, "cp");
    TransactionInfo nullCheckpoint = new TransactionInfo("id", 1L, null);

    assertFalse(nullId.isValid());
    assertEquals(0, nullId.getBytes().length);
    assertFalse(nullCheckpoint.isValid());
    assertEquals(0, nullCheckpoint.getBytes().length);
    assertEquals(0, TransactionInfo.getCheckpointIdCacheBytes().length);
  }
}
