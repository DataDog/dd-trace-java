package com.datadog.mlt.io;

import java.lang.ref.SoftReference;
import java.nio.ByteBuffer;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

/**
 * A {@linkplain ByteBuffer} backed implementation of {@linkplain LEB128Writer}. In order to
 * minimize GC churn instances share a thread-local {@linkplain ByteBuffer} instance which can grow
 * to accommodate the client requirements. Each instance keeps an offset to the backing {@linkplain
 * ByteBuffer} instance and all operations are done relative to that offset. This allows eg. have
 * 'nested' writers - a child writer will pick up where its parent finished and will restore the
 * position and limit back to the initial values upon calling {@linkplain
 * LEB128ByteBufferWriter#reset()}.
 */
@Slf4j
final class LEB128ByteBufferWriter extends AbstractLEB128Writer {
  private static final ThreadLocal<SoftReference<ByteBuffer>> BUFFER_REF;

  static {
    BUFFER_REF = ThreadLocal.withInitial(() -> new SoftReference<>(allocateBuffer(64 * 1024)));
  }

  private final int offset;
  private final int limit;

  LEB128ByteBufferWriter() {
    ByteBuffer buffer = getBuffer();
    offset = buffer.position();
    limit = buffer.limit();
  }

  @Override
  public void reset() {
    getBuffer().position(offset);
    getBuffer().limit(limit);
  }

  @Override
  public int writeFloat(int offset, float data) {
    offset += this.offset;
    ByteBuffer buffer = ensureCapacity((int) offset, 4);
    int originalPosition = buffer.position();
    buffer.position((int) offset);
    buffer.putFloat(data);
    if (originalPosition > buffer.position()) {
      buffer.position(originalPosition);
    }
    return position();
  }

  @Override
  public int writeDouble(int offset, double data) {
    offset += this.offset;
    ByteBuffer buffer = ensureCapacity((int) offset, 8);
    int originalPosition = buffer.position();
    buffer.position((int) offset);
    buffer.putDouble(data);
    if (originalPosition > buffer.position()) {
      buffer.position(originalPosition);
    }
    return position();
  }

  @Override
  public int writeByte(int offset, byte data) {
    offset += this.offset;
    ByteBuffer buffer = ensureCapacity((int) offset, 1);
    int originalPosition = buffer.position();
    buffer.position((int) offset);
    buffer.put(data);
    if (originalPosition > buffer.position()) {
      buffer.position(originalPosition);
    }
    return position();
  }

  @Override
  public int writeBytes(int offset, byte... data) {
    offset += this.offset;
    ByteBuffer buffer = ensureCapacity((int) offset, data.length);
    int originalPosition = buffer.position();
    buffer.position((int) offset);
    buffer.put(data, 0, data.length);
    if (originalPosition > buffer.position()) {
      buffer.position(originalPosition);
    }
    return position();
  }

  @Override
  public int writeShortRaw(int offset, short data) {
    offset += this.offset;
    ByteBuffer buffer = ensureCapacity((int) offset, 2);
    int originalPosition = buffer.position();
    buffer.position((int) offset);
    buffer.putShort(data);
    if (originalPosition > buffer.position()) {
      buffer.position(originalPosition);
    }
    return position();
  }

  @Override
  public int writeIntRaw(int offset, int data) {
    offset += this.offset;
    ByteBuffer buffer = ensureCapacity((int) offset, 4);
    int originalPosition = buffer.position();
    buffer.position((int) offset);
    buffer.putInt(data);
    if (originalPosition > buffer.position()) {
      buffer.position(originalPosition);
    }
    return position();
  }

  @Override
  public int writeLongRaw(int offset, long data) {
    offset += this.offset;
    ByteBuffer buffer = ensureCapacity((int) offset, 8);
    int originalPosition = buffer.position();
    buffer.position((int) offset);
    buffer.putLong(data);
    if (originalPosition > buffer.position()) {
      buffer.position(originalPosition);
    }
    return position();
  }

  @Override
  public void export(Consumer<ByteBuffer> consumer) {
    consumer.accept(getBuffer());
  }

  @Override
  public int position() {
    return getBuffer().position() - offset;
  }

  @Override
  public int capacity() {
    return getBuffer().capacity() - offset;
  }

  private ByteBuffer ensureCapacity(int offset, int dataLength) {
    ByteBuffer buffer = getBuffer();
    if (offset == -1) {
      offset = buffer.position();
    }
    if (offset + dataLength > buffer.capacity()) {
      int newCapacity = Math.max(buffer.capacity() * 2, offset + dataLength);
      log.warn(
          "{} capacity ({} bytes) exceeded. Reallocating internal buffer with new capacity {} bytes",
          this.getClass().getName(),
          buffer.capacity(),
          newCapacity);
      ByteBuffer newBuffer = allocateBuffer(newCapacity);
      buffer.flip();
      newBuffer.put(buffer);
      BUFFER_REF.set(new SoftReference<>(newBuffer));
      return newBuffer;
    }
    return buffer;
  }

  private static ByteBuffer allocateBuffer(int capacity) {
    return ByteBuffer.allocate(capacity);
  }

  private ByteBuffer getBuffer() {
    ByteBuffer buffer = null;
    while (buffer == null) {
      buffer = BUFFER_REF.get().get();
      if (buffer == null) {
        /*
         * The underlying ByteBuffer was released due to memory pressure.
         * Clear the TLS variable and try recreating the buffer.
         */
        log.debug(
            "Cleaning dangling reference to ByteBuffer from thread {}",
            Thread.currentThread().getName());
        BUFFER_REF.remove();
      }
    }
    return buffer;
  }
}
