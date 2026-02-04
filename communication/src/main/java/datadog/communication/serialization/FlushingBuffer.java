package datadog.communication.serialization;

import java.nio.ByteBuffer;

public final class FlushingBuffer implements StreamingBuffer {

  private final ByteBuffer buffer;
  private final ByteBufferConsumer consumer;

  private int messageCount;
  private int mark;

  public FlushingBuffer(int capacity, ByteBufferConsumer consumer) {
    this.buffer = ByteBuffer.allocate(capacity);
    this.consumer = consumer;
  }

  @Override
  public int capacity() {
    return buffer.capacity();
  }

  @Override
  public boolean isDirty() {
    return mark > 0;
  }

  @Override
  public void mark() {
    int current = buffer.position();
    if (current != mark) {
      // count only non-empty messages
      ++messageCount;
      mark = current;
    }
  }

  @Override
  public boolean flush() {
    if (messageCount == 0) {
      return false;
    }
    buffer.limit(mark);
    buffer.flip();
    ByteBuffer toPublish = buffer.slice();
    consumer.accept(messageCount, toPublish);
    reset();
    return true;
  }

  @Override
  public void put(byte b) {
    buffer.put(b);
  }

  @Override
  public void putShort(short s) {
    buffer.putShort(s);
  }

  @Override
  public void putChar(char c) {
    buffer.putChar(c);
  }

  @Override
  public void putInt(int i) {
    buffer.putInt(i);
  }

  @Override
  public void putLong(long l) {
    buffer.putLong(l);
  }

  @Override
  public void putFloat(float f) {
    buffer.putFloat(f);
  }

  @Override
  public void putDouble(double d) {
    buffer.putDouble(d);
  }

  @Override
  public void put(byte[] bytes) {
    buffer.put(bytes);
  }

  @Override
  public void put(byte[] bytes, int offset, int length) {
    buffer.put(bytes, offset, length);
  }

  @Override
  public void put(ByteBuffer buffer) {
    this.buffer.put(buffer);
  }

  @Override
  public void reset() {
    messageCount = 0;
    buffer.position(0);
    buffer.limit(buffer.capacity());
    mark = 0;
  }

  // for tests only
  int getMessageCount() {
    return messageCount;
  }
}
