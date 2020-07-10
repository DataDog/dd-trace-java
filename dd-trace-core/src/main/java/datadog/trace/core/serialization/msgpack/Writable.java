package datadog.trace.core.serialization.msgpack;

import java.nio.ByteBuffer;
import java.util.Map;

public interface Writable {
  void writeNull();

  void writeBoolean(boolean value);

  void writeObject(Object value, EncodingCache encodingCache);

  void writeMap(Map<? extends CharSequence, ?> map, EncodingCache encodingCache);

  void writeString(CharSequence s, EncodingCache encodingCache);

  void writeUTF8(byte[] string, int offset, int length);

  void writeUTF8(byte[] string);

  void writeBinary(byte[] binary, int offset, int length);

  void startMap(int elementCount);

  void startArray(int elementCount);

  void writeBinary(ByteBuffer buffer);

  void writeInt(int value);

  void writeLong(long value);

  void writeFloat(float value);

  void writeDouble(double value);
}
