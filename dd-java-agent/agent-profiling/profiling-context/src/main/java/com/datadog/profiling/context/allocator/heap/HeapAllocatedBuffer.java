package com.datadog.profiling.context.allocator.heap;

import com.datadog.profiling.context.LongIterator;
import com.datadog.profiling.context.allocator.AllocatedBuffer;
import java.nio.ByteBuffer;

final class HeapAllocatedBuffer implements AllocatedBuffer {
  private final HeapAllocator allocator;
  private final ByteBuffer buffer;

  HeapAllocatedBuffer(HeapAllocator allocator, int capacity) {
    this.allocator = allocator;
    buffer = ByteBuffer.allocate(capacity);
  }

  @Override
  public void release() {
    allocator.release(buffer.capacity());
  }

  @Override
  public int capacity() {
    return buffer.capacity();
  }

  @Override
  public boolean putLong(long value) {
    if (buffer.position() + 8 <= buffer.capacity()) {
      buffer.putLong(value);
      return true;
    }
    return false;
  }

  @Override
  public boolean putLong(int pos, long value) {
    if (pos + 8 <= buffer.capacity()) {
      buffer.putLong(pos, value);
      return true;
    }
    return false;
  }

  @Override
  public long getLong(int pos) {
    if (pos + 8 <= buffer.capacity()) {
      return buffer.getLong(pos);
    }
    return Long.MIN_VALUE;
  }

  @Override
  public LongIterator iterator() {
    ByteBuffer iterable = (ByteBuffer) buffer.duplicate().flip();
    return new LongIterator() {
      @Override
      public boolean hasNext() {
        return iterable.position() < iterable.capacity();
      }

      @Override
      public long next() {
        return iterable.getLong();
      }
    };
  }
}
