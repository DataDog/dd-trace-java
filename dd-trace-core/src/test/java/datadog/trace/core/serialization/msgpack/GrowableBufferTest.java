package datadog.trace.core.serialization.msgpack;

import static groovy.util.GroovyTestCase.assertEquals;

import datadog.trace.core.serialization.GrowableBuffer;
import java.nio.ByteBuffer;
import org.junit.Test;

public class GrowableBufferTest {

  @Test
  public void byteBufferTriggersResize() {
    GrowableBuffer gb = new GrowableBuffer(5);
    ByteBuffer buffer = ByteBuffer.allocate(20);
    for (int i = 0; i < 5; ++i) {
      buffer.putInt(i);
    }
    buffer.flip();
    gb.put(buffer);
    ByteBuffer contentsAfterResize = gb.slice();
    for (int i = 0; i < 5; ++i) {
      assertEquals(i, contentsAfterResize.getInt());
    }
  }

  @Test
  public void testBufferCapacity() {
    GrowableBuffer gb = new GrowableBuffer(5);
    assertEquals(5, gb.capacity());
    ByteBuffer buffer = ByteBuffer.allocate(20);
    for (int i = 0; i < 5; ++i) {
      buffer.putInt(i);
    }
    buffer.flip();
    gb.put(buffer);
    assertEquals(25, gb.capacity());
  }
}
