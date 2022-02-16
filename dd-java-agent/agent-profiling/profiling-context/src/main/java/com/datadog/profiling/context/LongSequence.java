package com.datadog.profiling.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LongSequence {
  private static final Logger log = LoggerFactory.getLogger(LongSequence.class);

  // with capacity increment c[n] = c[n -1] + 2 * c[n - 1] we can safely accommodate 100k elements
  private final Allocator.ChunkBuffer[] buffers = new Allocator.ChunkBuffer[10];
  private int bufferWriteSlot = 0;
  private int capacity = 0;
  private int capacityInChunks = 0;
  private int size = 0;
  private int sizeInBytes = 0;
  private int threshold = 0;
  private final Allocator allocator;

  private static int align(int size) {
    return (int)(Math.ceil(size / 8d) * 8);
  }

  public LongSequence(Allocator allocator) {
    this.allocator = allocator;
    this.bufferWriteSlot = -1;
  }

  public boolean add(long value) {
    if (bufferWriteSlot == -1) {
      capacityInChunks = 1;
      bufferWriteSlot = 0;
      Allocator.ChunkBuffer cBuffer = allocator.allocateChunks(capacityInChunks);
      buffers[bufferWriteSlot] = cBuffer;
      if (cBuffer != null) {
        capacity = cBuffer.capacity();
        threshold = align((int) (this.capacity * 0.75f));
      } else {
        capacity = 0;
        threshold = -1;
      }
    } else {
      if (threshold > -1 && sizeInBytes == threshold) {
        int newCapacity = 2 * capacityInChunks; // capacity stays aligned
        Allocator.ChunkBuffer cBuffer = allocator.allocateChunks(newCapacity);
        if (cBuffer != null) {
          buffers[bufferWriteSlot + 1] = cBuffer;
          capacityInChunks += newCapacity;
          capacity += cBuffer.capacity(); // update the sequence capacity
          threshold = align((int) (capacity * 0.75f)); // update the threshold
        } else {
          threshold = -1;
        }
      }
    }
    if (bufferWriteSlot == buffers.length || buffers[bufferWriteSlot] == null) {
      return false;
    }
    while (!buffers[bufferWriteSlot].putLong(value)) {
      bufferWriteSlot++;
      if (bufferWriteSlot == buffers.length || buffers[bufferWriteSlot] == null) {
        return false;
      }
    }

    size += 1;
    sizeInBytes += 8;
    return true;
  }

  public int size() {
    return size;
  }

  public void release() {
    for (Allocator.ChunkBuffer buffer : buffers) {
      if (buffer != null) {
        buffer.release();
      }
    }
  }

  public LongIterator iterator() {
    return new LongIterator() {
      int bufferReadSlot = 0;
      int allIndex = 0;
      LongIterator currentIterator = null;
      @Override
      public boolean hasNext() {
        if (bufferReadSlot > bufferWriteSlot || allIndex >= size) {
          return false;
        }
        if (currentIterator == null) {
          currentIterator = buffers[bufferReadSlot].iterator();
        }
        if (currentIterator.hasNext()) {
          return true;
        }
        do {
          if (++bufferReadSlot >= bufferWriteSlot) {
            return false;
          };
          currentIterator = buffers[bufferReadSlot].iterator();
        } while (!currentIterator.hasNext());
        return true;
      }

      @Override
      public long next() {
        if (currentIterator == null) {
          throw new IllegalStateException();
        }

        long value = currentIterator.next();
        allIndex++;
        return value;
      }
    };
  }
}
