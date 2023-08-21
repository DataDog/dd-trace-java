package datadog.communication.serialization;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class FlushingBufferTest {

  @Test
  public void testBufferCapacity() {
    assertEquals(5, new FlushingBuffer(5, (messageCount, buffer) -> {}).capacity());
  }
}
