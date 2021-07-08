package datadog.communication.serialization;

import static org.junit.Assert.assertEquals;

import java.nio.ByteBuffer;
import org.junit.Test;

public class FlushingBufferTest {

  @Test
  public void testBufferCapacity() {
    assertEquals(
        5,
        new FlushingBuffer(
                5,
                new ByteBufferConsumer() {
                  @Override
                  public void accept(int messageCount, ByteBuffer buffer) {}
                })
            .capacity());
  }
}
