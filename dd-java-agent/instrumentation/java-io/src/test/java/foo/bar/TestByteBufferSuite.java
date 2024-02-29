package foo.bar;

import java.nio.ByteBuffer;

public class TestByteBufferSuite {

  public static ByteBuffer wrap(final byte[] bytes) {
    return ByteBuffer.wrap(bytes);
  }

  public static byte[] array(final ByteBuffer buffer) {
    return buffer.array();
  }
}
