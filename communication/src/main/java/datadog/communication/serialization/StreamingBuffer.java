package datadog.communication.serialization;

import java.nio.ByteBuffer;

public interface StreamingBuffer {

  int capacity();

  boolean isDirty();

  /**
   * Completes the message written since the last mark.
   *
   * <p>Buffers must signal running out of room by throwing {@link java.nio.BufferOverflowException}
   * from the write that doesn't fit: {@code MsgPackWriter.format} relies on the exception to stop a
   * mapper before it updates per-payload state.
   *
   * @return {@code false} if the message didn't fit and was rewound (only for a buffer that
   *     discards writes instead of throwing)
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
