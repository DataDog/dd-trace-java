package com.datadog.profiling.context.allocator.heap;

import com.datadog.profiling.context.Allocator;
import com.datadog.profiling.context.allocator.AllocatedBuffer;

import java.util.concurrent.atomic.AtomicLong;

public final class HeapAllocator implements Allocator {
  private final int chunkSize;
  private final AtomicLong remaining;

  public HeapAllocator(int capacity, int chunkSize) {
    this.chunkSize = chunkSize;
    int numChunks = (int) Math.ceil(capacity / (double) chunkSize);
    this.remaining = new AtomicLong(numChunks * chunkSize);
  }

  @Override
  public int getChunkSize() {
    return chunkSize;
  }

  @Override
  public AllocatedBuffer allocate(int capacity) {
    long newRemaining = remaining.addAndGet(-capacity);
    if (newRemaining >= 0) {
      return new HeapAllocatedBuffer(this, capacity);
    } else {
      // no allocation happened; return the capacity back to the pool
      remaining.addAndGet(capacity);
    }
    return null;
  }

  @Override
  public AllocatedBuffer allocateChunks(int chunks) {
    return allocate(chunks * chunkSize);
  }

  void release(int size) {
    remaining.addAndGet(size);
  }
}
