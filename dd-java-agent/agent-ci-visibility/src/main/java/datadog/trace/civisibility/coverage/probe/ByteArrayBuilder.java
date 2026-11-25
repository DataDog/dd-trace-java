package datadog.trace.civisibility.coverage.probe;

import java.util.Arrays;

/** A growable byte array written in class file byte order. */
final class ByteArrayBuilder {

  byte[] data;
  int length;

  ByteArrayBuilder(int capacity) {
    data = new byte[Math.max(capacity, 16)];
  }

  private void ensure(int extra) {
    if (length + extra > data.length) {
      data = Arrays.copyOf(data, Math.max(data.length * 2, length + extra));
    }
  }

  ByteArrayBuilder putByte(int value) {
    ensure(1);
    data[length++] = (byte) value;
    return this;
  }

  ByteArrayBuilder putShort(int value) {
    ensure(2);
    data[length++] = (byte) (value >>> 8);
    data[length++] = (byte) value;
    return this;
  }

  ByteArrayBuilder putInt(int value) {
    ensure(4);
    data[length++] = (byte) (value >>> 24);
    data[length++] = (byte) (value >>> 16);
    data[length++] = (byte) (value >>> 8);
    data[length++] = (byte) value;
    return this;
  }

  ByteArrayBuilder putBytes(byte[] source, int offset, int count) {
    ensure(count);
    System.arraycopy(source, offset, data, length, count);
    length += count;
    return this;
  }

  void setShort(int position, int value) {
    data[position] = (byte) (value >>> 8);
    data[position + 1] = (byte) value;
  }

  void setInt(int position, int value) {
    data[position] = (byte) (value >>> 24);
    data[position + 1] = (byte) (value >>> 16);
    data[position + 2] = (byte) (value >>> 8);
    data[position + 3] = (byte) value;
  }

  byte[] toByteArray() {
    return Arrays.copyOf(data, length);
  }
}
