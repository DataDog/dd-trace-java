package com.datadog.profiling.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

public final class LongSequence {
  private static final Logger log = LoggerFactory.getLogger(LongSequence.class);

  // with capacity increment c[n] = c[n -1] + 2 * c[n - 1] we can safely accommodate 100k elements
  private final ByteBuffer[] buffer = new ByteBuffer[10];
  private int bufferWriteSlot = 0;
  private int bufferWriteIndex = 0;
  private int capacity = 0;
  private int size = 0;
  private int threshold = 0;

  private static int align(int size) {
    return (int)(Math.ceil(size / 8d) * 8);
  }

  public LongSequence() {
    this(10);
  }

  public LongSequence(int capacity) {
    this.capacity = Math.max(capacity, 10);
    bufferWriteSlot = -1;
//    this.buffer[bufferWriteSlot] = ByteBuffer.allocate(align(this.capacity * 8));
    this.threshold = align((int)(this.capacity * 0.75f));
  }

  public void add(long value) {
    return;
//    if (size == threshold) {
//      int newCapacity = 2 * capacity; // capacity stays aligned
//      buffer[bufferWriteSlot + 1] = ByteBuffer.allocate(newCapacity * 8);
//      capacity += newCapacity; // update the sequence capacity
//      threshold = align((int)(capacity * 0.75f)); // update the threshold
//    }
//    if (bufferWriteIndex >= buffer[bufferWriteSlot].capacity()) {
//      bufferWriteIndex = 0;
//      bufferWriteSlot++;
//    }
//    buffer[bufferWriteSlot].putLong(value);
//    size += 1;
//    bufferWriteIndex += 8;
  }

  public int size() {
    return size;
  }

  public LongIterator iterator() {
    return new LongIterator() {
      int bufferReadIndex =0;
      int bufferReadSlot = -1;
      int allIndex = 0;
      ByteBuffer currentBuffer = null;
      @Override
      public boolean hasNext() {
        while (bufferWriteSlot >= 0 && bufferReadIndex < bufferWriteIndex && bufferReadSlot <= bufferWriteSlot && allIndex < size) {
          if (bufferReadSlot < 0) {
            bufferReadSlot = 0;
            currentBuffer = (ByteBuffer) buffer[bufferReadSlot].duplicate().flip();
          }
          if (bufferReadIndex >= currentBuffer.limit()) {
            bufferReadSlot++;
            if (bufferReadSlot <= bufferWriteSlot) {
              bufferReadIndex = 0;
              currentBuffer = (ByteBuffer) buffer[bufferReadSlot].duplicate().flip();
            }
          } else {
            return true;
          }
        }
        return false;
      }

      @Override
      public long next() {
        try {
          long value = currentBuffer.getLong();
          allIndex++;
          bufferReadIndex += 8;
          return value;
        } catch (Throwable t) {
          System.out.println("===> " + bufferReadIndex);
          throw t;
        }
      }
    };
  }
}
