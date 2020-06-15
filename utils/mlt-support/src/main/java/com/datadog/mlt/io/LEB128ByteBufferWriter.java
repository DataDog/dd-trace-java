package com.datadog.mlt.io;

import java.nio.ByteBuffer;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class LEB128ByteBufferWriter extends AbstractLEB128Writer {
  private ByteBuffer buffer;

  LEB128ByteBufferWriter(int initialCapacity) {
    this.buffer = allocateBuffer(initialCapacity);
  }

  @Override
  public void reset() {
    buffer.clear();
  }

  @Override
  public long writeFloat(long offset, float data) {
    ensureCapacity((int) offset, 4);
    int originalPosition = buffer.position();
    buffer.position((int) offset);
    buffer.putFloat(data);
    if (originalPosition > buffer.position()) {
      buffer.position(originalPosition);
    }
    return buffer.position();
  }

  @Override
  public long writeDouble(long offset, double data) {
    ensureCapacity((int) offset, 8);
    int originalPosition = buffer.position();
    buffer.position((int) offset);
    buffer.putDouble(data);
    if (originalPosition > buffer.position()) {
      buffer.position(originalPosition);
    }
    return buffer.position();
  }

  @Override
  public long writeByte(long offset, byte data) {
    ensureCapacity((int) offset, 1);
    int originalPosition = buffer.position();
    buffer.position((int) offset);
    buffer.put(data);
    if (originalPosition > buffer.position()) {
      buffer.position(originalPosition);
    }
    return buffer.position();
  }

  @Override
  public long writeBytes(long offset, byte... data) {
    ensureCapacity((int) offset, data.length);
    int originalPosition = buffer.position();
    buffer.position((int) offset);
    buffer.put(data, 0, data.length);
    if (originalPosition > buffer.position()) {
      buffer.position(originalPosition);
    }
    return buffer.position();
  }

  @Override
  public long writeShortRaw(long offset, short data) {
    ensureCapacity((int) offset, 2);
    int originalPosition = buffer.position();
    buffer.position((int) offset);
    buffer.putShort(data);
    if (originalPosition > buffer.position()) {
      buffer.position(originalPosition);
    }
    return buffer.position();
  }

  @Override
  public long writeIntRaw(long offset, int data) {
    ensureCapacity((int) offset, 4);
    int originalPosition = buffer.position();
    buffer.position((int) offset);
    buffer.putInt(data);
    if (originalPosition > buffer.position()) {
      buffer.position(originalPosition);
    }
    return buffer.position();
  }

  @Override
  public long writeLongRaw(long offset, long data) {
    ensureCapacity((int) offset, 8);
    int originalPosition = buffer.position();
    buffer.position((int) offset);
    buffer.putLong(data);
    if (originalPosition > buffer.position()) {
      buffer.position(originalPosition);
    }
    return buffer.position();
  }

  @Override
  public void export(Consumer<ByteBuffer> consumer) {
    consumer.accept(buffer);
  }

  @Override
  public int position() {
    return buffer.position();
  }

  @Override
  public int capacity() {
    return buffer.capacity();
  }

  private void ensureCapacity(int offset, int dataLength) {
    if (offset + dataLength > buffer.capacity()) {
      int newCapacity = buffer.capacity() * 2;
      log.warn(
          "{} capacity ({} bytes) exceeded. Reallocating internal buffer with new capacity {} bytes",
          this.getClass().getName(),
          buffer.capacity(),
          newCapacity);
      ByteBuffer newBuffer = allocateBuffer(newCapacity);
      buffer.flip();
      newBuffer.put(buffer);
      buffer = newBuffer;
    }
  }

  private static ByteBuffer allocateBuffer(int capacity) {
    return ByteBuffer.allocateDirect(capacity);
  }
}
