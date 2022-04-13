package datadog.communication.serialization;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.nio.ByteBuffer;
import java.util.Map;

public interface Writable {
  void writeNull();

  void writeBoolean(boolean value);

  void writeObject(Object value, EncodingCache encodingCache);

  void writeObjectString(Object value, EncodingCache encodingCache);

  void writeMap(Map<? extends CharSequence, ?> map, EncodingCache encodingCache);

  void writeString(CharSequence s, EncodingCache encodingCache);

  void writeUTF8(byte[] string, int offset, int length);

  void writeUTF8(byte[] string);

  void writeUTF8(UTF8BytesString string);

  void writeBinary(byte[] binary);

  void writeBinary(byte[] binary, int offset, int length);

  /**
   * Start a part of the message containing key-value pairs
   *
   * @param elementCount how many key-value pairs in the section of the message
   */
  void startMap(int elementCount);

  /**
   * Start a part of the message containing fields or values at ordinal positions
   *
   * @param elementCount how many fields in the section of the message
   */
  void startStruct(int elementCount);

  /**
   * Start a part of the message containing positional values
   *
   * @param elementCount how many array elements in the section of the message
   */
  void startArray(int elementCount);

  void writeBinary(ByteBuffer buffer);

  void writeInt(int value);

  void writeSignedInt(int value);

  void writeLong(long value);

  void writeUnsignedLong(long value);

  void writeSignedLong(long value);

  void writeFloat(float value);

  void writeDouble(double value);
}
