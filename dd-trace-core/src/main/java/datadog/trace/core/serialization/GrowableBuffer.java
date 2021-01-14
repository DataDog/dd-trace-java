package datadog.trace.core.serialization;

import java.nio.ByteBuffer;

public final class GrowableBuffer implements StreamingBuffer {

  private ByteBuffer buffer;
  private int messageCount;

  public GrowableBuffer(int initialCapacity) {
    this.buffer = ByteBuffer.allocate(initialCapacity);
  }

  public ByteBuffer slice() {
    buffer.flip();
    return buffer.slice();
  }

  public int messageCount() {
    return messageCount;
  }

  public void reset() {
    messageCount = 0;
    buffer.position(0);
    buffer.limit(buffer.capacity());
  }

  @Override
  public boolean isDirty() {
    return messageCount > 0;
  }

  @Override
  public void mark() {
    ++messageCount;
  }

  @Override
  public boolean flush() {
    return false;
  }

  @Override
  public void put(byte b) {
    checkCapacity(1);
    buffer.put(b);
  }

  @Override
  public void putShort(short s) {
    checkCapacity(2);
    buffer.putShort(s);
  }

  @Override
  public void putChar(char c) {
    checkCapacity(2);
    buffer.putChar(c);
  }

  @Override
  public void putInt(int i) {
    checkCapacity(4);
    buffer.putInt(i);
  }

  @Override
  public void putLong(long l) {
    checkCapacity(8);
    buffer.putLong(l);
  }

  @Override
  public void putFloat(float f) {
    checkCapacity(4);
    buffer.putFloat(f);
  }

  @Override
  public void putDouble(double d) {
    checkCapacity(8);
    buffer.putDouble(d);
  }

  @Override
  public void put(byte[] bytes) {
    checkCapacity(bytes.length);
    buffer.put(bytes);
  }

  @Override
  public void put(byte[] bytes, int offset, int length) {
    checkCapacity(length);
    buffer.put(bytes, offset, length);
  }

  @Override
  public void put(ByteBuffer buffer) {
    checkCapacity(buffer.remaining());
    this.buffer.put(buffer);
  }

  private void checkCapacity(int required) {
    if (buffer.remaining() < required) {
      resize();
    }
  }

  private void resize() {
    ByteBuffer newBuffer = ByteBuffer.allocate(buffer.capacity() * 2);
    buffer.flip();
    newBuffer.put(buffer);
    buffer = newBuffer;
  }
}
