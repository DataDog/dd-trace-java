package datadog.communication.serialization;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FlushingBufferTest {

  @Test
  public void testBufferCapacity() {
    assertEquals(5, new FlushingBuffer(5, (messageCount, buffer) -> {}).capacity());
  }
}
