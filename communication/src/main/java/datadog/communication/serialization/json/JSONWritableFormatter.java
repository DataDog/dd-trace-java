package datadog.communication.serialization.json;

import datadog.communication.serialization.EncodingCache;
import datadog.communication.serialization.Mapper;
import datadog.communication.serialization.WritableFormatter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.nio.ByteBuffer;
import java.util.Map;

public class JSONWritableFormatter implements WritableFormatter {

  @Override
  public <T> boolean format(T message, Mapper<T> mapper) {
    return false;
  }

  @Override
  public void flush() {

  }

  @Override
  public void writeNull() {

  }

  @Override
  public void writeBoolean(boolean value) {

  }

  @Override
  public void writeObject(Object value, EncodingCache encodingCache) {

  }

  @Override
  public void writeObjectString(Object value, EncodingCache encodingCache) {

  }

  @Override
  public void writeMap(Map<? extends CharSequence, ?> map, EncodingCache encodingCache) {

  }

  @Override
  public void writeString(CharSequence s, EncodingCache encodingCache) {

  }

  @Override
  public void writeUTF8(byte[] string, int offset, int length) {

  }

  @Override
  public void writeUTF8(byte[] string) {

  }

  @Override
  public void writeUTF8(UTF8BytesString string) {

  }

  @Override
  public void writeBinary(byte[] binary) {

  }

  @Override
  public void writeBinary(byte[] binary, int offset, int length) {

  }

  @Override
  public void startMap(int elementCount) {

  }

  @Override
  public void startStruct(int elementCount) {

  }

  @Override
  public void startArray(int elementCount) {

  }

  @Override
  public void writeBinary(ByteBuffer buffer) {

  }

  @Override
  public void writeInt(int value) {

  }

  @Override
  public void writeSignedInt(int value) {

  }

  @Override
  public void writeLong(long value) {

  }

  @Override
  public void writeUnsignedLong(long value) {

  }

  @Override
  public void writeSignedLong(long value) {

  }

  @Override
  public void writeFloat(float value) {

  }

  @Override
  public void writeDouble(double value) {

  }
}
