package datadog.trace.core.serialization.msgpack;

import static org.junit.Assert.assertEquals;

import datadog.trace.core.serialization.ByteBufferConsumer;
import datadog.trace.core.serialization.FlushingBuffer;
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
