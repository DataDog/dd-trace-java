package datadog.trace.core.serialization;

import java.nio.ByteBuffer;

public interface StreamingBuffer {

  boolean isDirty();

  void mark();

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
}
