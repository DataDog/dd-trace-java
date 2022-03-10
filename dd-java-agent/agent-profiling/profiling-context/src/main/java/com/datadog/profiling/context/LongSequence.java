package com.datadog.profiling.context;

import com.datadog.profiling.context.allocator.AllocatedBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LongSequence {
  private static final Logger log = LoggerFactory.getLogger(LongSequence.class);

  private class LongIteratorImpl implements LongIterator {
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
        if (++bufferReadSlot > bufferWriteSlot) {
          return false;
        }
        ;
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
  }

  // with capacity increment c[n] = c[n -1] + 2 * c[n - 1] we can safely accommodate 100k elements
  private AllocatedBuffer[] buffers = new AllocatedBuffer[10];
  private int[] bufferBoundaryMap = new int[10];

  private int bufferInitSlot = 0;
  private int bufferWriteSlot = 0;
  private int capacity = 0;
  private int capacityInChunks = 0;
  private int size = 0;
  private int sizeInBytes = 0;
  private int threshold = 0;
  private final Allocator allocator;

  private static int align(int size) {
    return (int) (Math.ceil(size / 8d) * 8);
  }

  private final AtomicBoolean released = new AtomicBoolean(false);

  public LongSequence(Allocator allocator) {
    this.allocator = allocator;
    this.bufferWriteSlot = -1;
  }

  public int add(long value) {
    if (released.get()) {
      return -1;
    }

    if (bufferWriteSlot == -1) {
      capacityInChunks = 1;
      bufferWriteSlot = 0;
      bufferInitSlot = 0;
      AllocatedBuffer cBuffer = allocator.allocateChunks(capacityInChunks);
      buffers[bufferWriteSlot] = cBuffer;
      if (cBuffer != null) {
        capacity = cBuffer.capacity();
        threshold = align((int) (this.capacity * 0.75f));
        bufferBoundaryMap[bufferWriteSlot] = capacity - 1;
      } else {
        capacity = 0;
        threshold = -1;
      }
    } else {
      if (threshold > -1 && sizeInBytes == threshold) {
        int newCapacity = 2 * capacityInChunks; // capacity stays aligned
        AllocatedBuffer cBuffer = allocator.allocateChunks(newCapacity);
        if (cBuffer != null) {
          bufferInitSlot = bufferWriteSlot + 1;
          buffers[bufferInitSlot] = cBuffer;
          capacityInChunks += newCapacity;
          capacity += cBuffer.capacity(); // update the sequence capacity
          threshold = align((int) (capacity * 0.75f)); // update the threshold
          bufferBoundaryMap[bufferInitSlot] = capacity - 1;
        } else {
          threshold = -1;
        }
      }
    }
    if (bufferWriteSlot == buffers.length || buffers[bufferWriteSlot] == null) {
      return 0;
    }
    while (!buffers[bufferWriteSlot].putLong(value)) {
      bufferWriteSlot++;
      if (bufferWriteSlot == buffers.length || buffers[bufferWriteSlot] == null) {
        return 0;
      }
    }

    size += 1;
    sizeInBytes += 8;
    return sizeInBytes;
  }

  public boolean set(int index, long value) {
    int[] decoded = decodeBufferIndex(index);
    if (decoded != null) {
      return buffers[decoded[0]].putLong(decoded[1], value);
    }
    return false;
  }

  public long get(int index) {
    int[] decoded = decodeBufferIndex(index);
    if (decoded != null) {
      return buffers[decoded[0]].getLong(decoded[1]);
    }
    return Long.MIN_VALUE;
  }

  private int[] decodeBufferIndex(int index) {
    index *= 8; // 8 bytes per 1 long

    // shortcut for only one slot in use
    if (bufferInitSlot == 0) {
      return index <= bufferBoundaryMap[0] ? new int[] {0, index} : null;
    }

    // shortcut to linear search for a small number of slots in use
    if (bufferInitSlot < 5) {
      int slot = 0;
      while (slot <= bufferInitSlot && bufferBoundaryMap[bufferInitSlot] < index) {
        slot++;
      }
      if (slot <= bufferInitSlot) {
        return new int[] {slot, index - bufferBoundaryMap[slot]};
      }
      return null;
    }

    // use binary search
    int slot = Arrays.binarySearch(bufferBoundaryMap, index);
    if (slot > 0) {
      return new int[] {slot, index - bufferBoundaryMap[slot - 1] - 1};
    } else if (slot == 0) {
      return new int[] {slot, index};
    } else {
      slot = 1 - slot;
      if (slot <= bufferInitSlot) {
        return new int[] {slot, index - bufferBoundaryMap[slot - 1] - 1};
      }
      return null;
    }
  }

  public int size() {
    return size;
  }

  public void release() {
    if (released.compareAndSet(false, true)) {
      for (int i = 0; i < buffers.length; i++) {
        AllocatedBuffer buffer = buffers[i];
        if (buffer != null) {
          buffer.release();
        }
      }
      // this (LongSequence) instance will get stuck in the TLS reference
      // clear out the buffer slots to allow the most of the retained data to be GCed
      buffers = null;
      bufferBoundaryMap = null;
      bufferInitSlot = -1;
    }
  }

  public LongIterator iterator() {
    return new LongIteratorImpl();
  }
}
