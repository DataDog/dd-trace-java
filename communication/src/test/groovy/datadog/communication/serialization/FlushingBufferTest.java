package datadog.communication.serialization;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class FlushingBufferTest {

  @Test
  public void testBufferCapacity() {
    assertEquals(5, new FlushingBuffer(5, (messageCount, buffer) -> {}).capacity());
  }

  @Test
  public void testMessageCount() {
    FlushingBuffer fb = new FlushingBuffer(10, (messageCount, buffer) -> {});

    // initial counter
    assertEquals(0, fb.getMessageCount());

    fb.mark();
    fb.mark();

    // counter doesn't change if no data pushed into the buffer
    assertEquals(0, fb.getMessageCount());

    fb.put((byte) 1);
    // still zero because the message counter increases on mark
    assertEquals(0, fb.getMessageCount());

    fb.mark();
    // expect increased message counter
    assertEquals(1, fb.getMessageCount());

    fb.mark();
    fb.mark();
    // no change to the counter expected for consecutive mark calls

    fb.putChar('a');
    fb.putChar('b');
    fb.putChar('c');
    // no change to the message counter expected before mark call
    assertEquals(1, fb.getMessageCount());

    fb.mark();
    // expect increased message counter
    assertEquals(2, fb.getMessageCount());

    fb.mark();
    fb.mark();
    // no change to the counter expected for consecutive mark calls
    assertEquals(2, fb.getMessageCount());
  }
}
