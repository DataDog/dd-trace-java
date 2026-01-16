package datadog.communication.serialization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

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

  @Test
  public void testBufferReset() {
    GrowableBuffer gb = new GrowableBuffer(5);
    gb.put((byte) 1);
    gb.mark();
    assertTrue(gb.isDirty());

    gb.reset();
    assertFalse(gb.isDirty());

    gb.put((byte) 42);
    ByteBuffer slice = gb.slice();
    assertEquals(0, gb.messageCount());
    assertEquals(42, slice.get());
  }

  @Test
  public void testFlush() {
    GrowableBuffer gb = new GrowableBuffer(5);
    gb.put((byte) 1);
    assertFalse(gb.flush());
  }

  @Test
  public void testPutVariants() {
    GrowableBuffer gb = new GrowableBuffer(5);
    gb.putShort((short) 1);
    gb.putChar((char) 2);
    gb.putInt(4);
    gb.putLong(8L);
    gb.putFloat(16.0f);
    gb.putDouble(32.0);
    gb.put(new byte[] {64});
    gb.put(new byte[] {-128}, 0, 1);

    ByteBuffer slice = gb.slice();
    assertEquals((short) 1, slice.getShort());
    assertEquals((char) 2, slice.getChar());
    assertEquals(4, slice.getInt());
    assertEquals(8L, slice.getLong());
    assertEquals(16.0f, slice.getFloat());
    assertEquals(32.0, slice.getDouble());
    assertEquals((byte) 64, slice.get());
    assertEquals((byte) -128, slice.get());
  }
}
