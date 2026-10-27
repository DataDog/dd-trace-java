package datadog.communication.serialization;

import java.nio.ByteBuffer;

public interface StreamingBuffer {

  int capacity();

  boolean isDirty();

  /**
   * Completes the message written since the last mark.
   *
   * @return {@code false} if the message didn't fit and was rewound. Buffers that signal running
   *     out of room by throwing {@link java.nio.BufferOverflowException} always return {@code
   *     true}.
   */
  boolean mark();

  boolean flush();

  void put(byte b);

  void putShort(short s);

  void putChar(char c);

  void putInt(int i);

  void putLong(long l);

  void putFloat(float f);

  void putDouble(double d);

  void put(byte[] bytes);

  void put(byte[] bytes, int offset, int length);

  void put(ByteBuffer buffer);

  /** Discards everything written since the last {@link #mark()}, keeping completed messages. */
  void rewind();

  void reset();
}
