package datadog.trace.instrumentation.liberty.appsec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.wsspi.bytebuffer.WsByteBuffer;
import datadog.trace.instrumentation.liberty.appsec.LibertyBlockingHelper.WsByteBufferImpl;
import java.nio.ByteBuffer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class WsByteBufferImplTest {

  private static final byte[] DATA = {1, 2, 3, 4, 5, 6, 7, 8};

  private static WsByteBufferImpl newBuffer() {
    return new WsByteBufferImpl(ByteBuffer.wrap(DATA.clone()));
  }

  @Test
  void stateReflectsWrappedBuffer() {
    ByteBuffer bb = ByteBuffer.wrap(DATA.clone());
    WsByteBufferImpl buffer = new WsByteBufferImpl(bb);

    assertSame(bb.array(), buffer.array());
    assertEquals(0, buffer.arrayOffset());
    assertEquals(0, buffer.position());
    assertEquals(8, buffer.limit());
    assertEquals(8, buffer.capacity());
    assertEquals(8, buffer.remaining());
    assertTrue(buffer.hasRemaining());
    assertTrue(buffer.hasArray());
    assertFalse(buffer.isDirect());
    assertSame(bb, buffer.getWrappedByteBuffer());
    assertSame(bb, buffer.getWrappedByteBufferNonSafe());
  }

  @Test
  void positionAndLimitDelegateToWrappedBuffer() {
    WsByteBufferImpl buffer = newBuffer();

    assertSame(buffer, buffer.position(2));
    assertSame(buffer, buffer.limit(6));

    assertEquals(2, buffer.position());
    assertEquals(6, buffer.limit());
    assertEquals(4, buffer.remaining());
  }

  @Test
  void sliceReflectsRemainingBytes() {
    WsByteBufferImpl buffer = newBuffer();
    buffer.position(3);

    WsByteBuffer slice = buffer.slice();

    assertEquals(5, slice.remaining());
    assertEquals(0, slice.position());
    assertEquals(4, slice.get());
  }

  @Test
  void readOnlyFlags() {
    WsByteBufferImpl buffer = newBuffer();

    assertTrue(buffer.isReadOnly());
    assertTrue(buffer.getReadOnly());
  }

  @Test
  void getReadsBytes() {
    WsByteBufferImpl buffer = newBuffer();

    byte[] first = new byte[3];
    assertSame(buffer, buffer.get(first));
    assertArrayEquals(new byte[] {1, 2, 3}, first);
    assertEquals(3, buffer.position());

    byte[] second = new byte[4];
    buffer.get(second, 1, 2);
    assertArrayEquals(new byte[] {0, 4, 5, 0}, second);

    assertEquals(6, buffer.get());
    assertEquals(8, buffer.get(7));
  }

  @Test
  void statusDefaultsToOneAndIsSettable() {
    WsByteBufferImpl buffer = newBuffer();

    assertEquals(1, buffer.getStatus());
    buffer.setStatus(5);
    assertEquals(5, buffer.getStatus());
  }

  @Test
  void getIntNoArgReadsASingleByte() {
    // Current behavior: getInt() delegates to ByteBuffer.get() and advances by one byte only.
    WsByteBufferImpl buffer = newBuffer();

    assertEquals(1, buffer.getInt());
    assertEquals(1, buffer.position());
  }

  static Stream<org.junit.jupiter.params.provider.Arguments> mutators() {
    return Stream.of(
        arg("put(byte)", b -> b.put((byte) 1)),
        arg("put(byte[])", b -> b.put(new byte[1])),
        arg("put(byte[],int,int)", b -> b.put(new byte[1], 0, 1)),
        arg("put(int,byte)", b -> b.put(0, (byte) 1)),
        arg("put(ByteBuffer)", b -> b.put(ByteBuffer.allocate(1))),
        arg("put(WsByteBuffer)", b -> b.put((WsByteBuffer) newBuffer())),
        arg("put(WsByteBuffer[])", b -> b.put(new WsByteBuffer[0])),
        arg("putChar(char)", b -> b.putChar('a')),
        arg("putChar(int,char)", b -> b.putChar(0, 'a')),
        arg("putChar(char[])", b -> b.putChar(new char[1])),
        arg("putChar(char[],int,int)", b -> b.putChar(new char[1], 0, 1)),
        arg("putDouble(double)", b -> b.putDouble(1d)),
        arg("putDouble(int,double)", b -> b.putDouble(0, 1d)),
        arg("putFloat(float)", b -> b.putFloat(1f)),
        arg("putFloat(int,float)", b -> b.putFloat(0, 1f)),
        arg("putInt(int)", b -> b.putInt(1)),
        arg("putInt(int,int)", b -> b.putInt(0, 1)),
        arg("putLong(long)", b -> b.putLong(1L)),
        arg("putLong(int,long)", b -> b.putLong(0, 1L)),
        arg("putShort(short)", b -> b.putShort((short) 1)),
        arg("putShort(int,short)", b -> b.putShort(0, (short) 1)),
        arg("putString(String)", b -> b.putString("a")),
        arg("clear()", WsByteBuffer::clear),
        arg("duplicate()", WsByteBuffer::duplicate));
  }

  private static org.junit.jupiter.params.provider.Arguments arg(
      String name, java.util.function.Consumer<WsByteBuffer> action) {
    return org.junit.jupiter.params.provider.Arguments.of(name, action);
  }

  @ParameterizedTest(name = "{0} is unsupported")
  @MethodSource("mutators")
  void mutatingOperationsAreUnsupported(String name, java.util.function.Consumer<WsByteBuffer> op) {
    WsByteBufferImpl buffer = newBuffer();

    assertThrows(UnsupportedOperationException.class, () -> op.accept(buffer), name);
    // the wrapped data must be untouched
    assertArrayEquals(DATA, buffer.array());
  }
}
