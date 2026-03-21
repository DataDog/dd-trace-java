package datadog.trace.core.datastreams;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.api.datastreams.TransactionInfo;
import datadog.trace.core.test.DDCoreSpecification;
import org.junit.jupiter.api.Test;

class TransactionContainerTest extends DDCoreSpecification {

  @Test
  void testWithNoResize() {
    TransactionInfo.resetCache();
    TransactionContainer container = new TransactionContainer(1024);
    container.add(new TransactionInfo("1", 1, "1"));
    container.add(new TransactionInfo("2", 2, "2"));
    byte[] data = container.getData();

    assertEquals(22, data.length);
    assertArrayEquals(
        new byte[] {1, 0, 0, 0, 0, 0, 0, 0, 1, 1, 49, 2, 0, 0, 0, 0, 0, 0, 0, 2, 1, 50}, data);
  }

  @Test
  void testWithResize() {
    TransactionInfo.resetCache();
    TransactionContainer container = new TransactionContainer(10);
    container.add(new TransactionInfo("1", 1, "1"));
    container.add(new TransactionInfo("2", 2, "2"));
    byte[] data = container.getData();

    assertEquals(22, data.length);
    assertArrayEquals(
        new byte[] {1, 0, 0, 0, 0, 0, 0, 0, 1, 1, 49, 2, 0, 0, 0, 0, 0, 0, 0, 2, 1, 50}, data);
  }

  @Test
  void testCheckpointMap() {
    TransactionInfo.resetCache();
    new TransactionInfo("1", 1, "1");
    new TransactionInfo("2", 2, "2");
    byte[] data = TransactionInfo.getCheckpointIdCacheBytes();

    assertEquals(6, data.length);
    assertArrayEquals(new byte[] {1, 1, 49, 2, 1, 50}, data);
  }
}
