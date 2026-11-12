package datadog.communication.serialization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.ByteBuffer;
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
    assertEquals(0, fb.messageCount());

    fb.mark();
    fb.mark();

    // counter doesn't change if no data pushed into the buffer
    assertEquals(0, fb.messageCount());

    fb.put((byte) 1);
    // still zero because the message counter increases on mark
    assertEquals(0, fb.messageCount());

    fb.mark();
    // expect increased message counter
    assertEquals(1, fb.messageCount());

    fb.mark();
    fb.mark();
    // no change to the counter expected for consecutive mark calls

    fb.putChar('a');
    fb.putChar('b');
    fb.putChar('c');
    // no change to the message counter expected before mark call
    assertEquals(1, fb.messageCount());

    fb.mark();
    // expect increased message counter
    assertEquals(2, fb.messageCount());

    fb.mark();
    fb.mark();
    // no change to the counter expected for consecutive mark calls
    assertEquals(2, fb.messageCount());
  }

  @Test
  public void completedMessagesExcludesUnfinishedMessageWithoutChangingWritePosition() {
    FlushingBuffer buffer = new FlushingBuffer(8, (count, data) -> {});
    buffer.put((byte) 1);
    buffer.mark();
    buffer.put((byte) 2);

    assertEquals(1, buffer.completedMessages().remaining());
    assertEquals(1, buffer.completedMessages().get());

    buffer.put((byte) 3);
    buffer.mark();
    assertEquals(3, buffer.completedMessages().remaining());
    assertEquals(2, buffer.completedMessages().get(1));
    assertEquals(3, buffer.completedMessages().get(2));
  }

  @Test
  public void rewindDiscardsBytesWrittenSinceLastMark() {
    FlushingBuffer buffer = new FlushingBuffer(8, (count, data) -> {});
    buffer.put((byte) 1);
    buffer.mark();
    buffer.put((byte) 2);
    buffer.put((byte) 3);

    buffer.rewind();
    buffer.put((byte) 4);
    buffer.mark();

    assertEquals(2, buffer.messageCount());
    ByteBuffer completed = buffer.completedMessages();
    assertEquals(2, completed.remaining());
    assertEquals(1, completed.get(0));
    assertEquals(4, completed.get(1));
  }

  @Test
  public void rewindBeforeAnyMarkLeavesBufferEmpty() {
    FlushingBuffer buffer = new FlushingBuffer(8, (count, data) -> {});
    buffer.put((byte) 1);

    buffer.rewind();

    assertFalse(buffer.isDirty());
    assertEquals(0, buffer.completedMessages().remaining());
    assertFalse(buffer.flush());
  }
}
