package datadog.trace.core.datastreams;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import datadog.trace.api.datastreams.TransactionInfo;
import datadog.trace.api.datastreams.TransactionInfoTestBridge;
import datadog.trace.core.DDCoreJavaSpecification;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

public class TransactionContainerTest extends DDCoreJavaSpecification {

  private static final byte[] EXPECTED_CONTAINER_DATA =
      new byte[] {1, 0, 0, 0, 0, 0, 0, 0, 1, 1, 49, 2, 0, 0, 0, 0, 0, 0, 0, 2, 1, 50};

  @Test
  void testWithNoResize() {
    TransactionInfoTestBridge.resetCache();
    TransactionContainer container = new TransactionContainer(1024);
    container.add(new TransactionInfo("1", 1, "1"));
    container.add(new TransactionInfo("2", 2, "2"));
    byte[] data = container.getData();

    assertEquals(22, data.length);
    assertArrayEquals(EXPECTED_CONTAINER_DATA, data);
  }

  @Test
  void testWithResize() {
    TransactionInfoTestBridge.resetCache();
    TransactionContainer container = new TransactionContainer(10);
    container.add(new TransactionInfo("1", 1, "1"));
    container.add(new TransactionInfo("2", 2, "2"));
    byte[] data = container.getData();

    assertEquals(22, data.length);
    assertArrayEquals(EXPECTED_CONTAINER_DATA, data);
  }

  @Test
  void testCheckpointMap() {
    TransactionInfoTestBridge.resetCache();
    new TransactionInfo("1", 1, "1");
    new TransactionInfo("2", 2, "2");
    byte[] data = TransactionInfo.getCheckpointIdCacheBytes();

    assertEquals(6, data.length);
    assertArrayEquals(new byte[] {1, 1, 49, 2, 1, 50}, data);
  }

  @Test
  void testLongTransactionIdPreservesFramingAndResizesSmallInitialBuffer() {
    TransactionInfoTestBridge.resetCache();
    char[] chars = new char[260];
    Arrays.fill(chars, 'a');
    String longId = new String(chars);

    TransactionContainer container = new TransactionContainer(10);
    container.add(new TransactionInfo(longId, 100L, "cp1"));
    container.add(new TransactionInfo("second", 200L, "cp2"));

    ByteBuffer buffer = ByteBuffer.wrap(container.getData()).order(ByteOrder.BIG_ENDIAN);
    assertEquals(1, Byte.toUnsignedInt(buffer.get()));
    assertEquals(100L, buffer.getLong());
    int firstLen = Byte.toUnsignedInt(buffer.get());
    assertEquals(255, firstLen);
    byte[] firstBytes = new byte[firstLen];
    buffer.get(firstBytes);
    assertEquals(longId.substring(0, 255), new String(firstBytes, UTF_8));

    assertEquals(2, Byte.toUnsignedInt(buffer.get()));
    assertEquals(200L, buffer.getLong());
    int secondLen = Byte.toUnsignedInt(buffer.get());
    assertEquals(6, secondLen);
    byte[] secondBytes = new byte[secondLen];
    buffer.get(secondBytes);
    assertEquals("second", new String(secondBytes, UTF_8));
    assertFalse(buffer.hasRemaining());
  }

  @Test
  void testInvalidTransactionInfoIsIgnored() {
    TransactionInfoTestBridge.resetCache();
    for (int i = 1; i <= 254; i++) {
      new TransactionInfo("tx-" + i, i, "cp-" + i);
    }
    TransactionInfo invalid = new TransactionInfo("tx-overflow", 999L, "cp-overflow");
    assertFalse(invalid.isValid());

    TransactionContainer container = new TransactionContainer(64);
    container.add(invalid);
    assertEquals(0, container.getSize());
    assertEquals(0, container.getData().length);
  }
}
