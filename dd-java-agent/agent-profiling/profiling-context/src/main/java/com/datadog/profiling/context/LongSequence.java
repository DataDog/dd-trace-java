package com.datadog.profiling.context;

import com.datadog.profiling.context.allocator.AllocatedBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A growing sequence of long values.<br>
 * Allows adding new values, rewriting values at a given position, retrieving values from a position
 * and creating {@linkplain LongIterator} over the sequence.
 *
 * <p>This class is not thread-safe in a generic way. The only concurrent access that is allowed is
 * doing 'release' while a sequence is manipulated and vice versa. For all other combinations of
 * method invocations the results are not defined. The caller should take care of proper
 * synchronization, if necessary.
 */
final class LongSequence {
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
  private final PositionDecoder positionDecoder = PositionDecoder.getInstance();

  private static int align(int size) {
    return (int) (Math.ceil(size / 8d) * 8);
  }

  private AtomicInteger state = new AtomicInteger(0);

  public LongSequence(Allocator allocator) {
    this.allocator = allocator;
    this.bufferWriteSlot = -1;
  }

  public int add(long value) {
    // check'n'update the state - if it is non-negative, increment the counter and proceed,
    // otherwise bail out
    if (state.updateAndGet(prev -> prev >= 0 ? prev + 1 : prev) < 0) {
      // bail out if this instance was already released
      return -1;
    }

    try {
      if (bufferWriteSlot == -1) {
        // first write; initialize the data structure
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
          // we hit the threshold - let's prepare the next-in-line buffer
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
      return 1;
    } finally {
      // check'n'update the state - if it is negative before update, perform release, otherwise just
      // decrement the counter
      if (state.getAndUpdate(prev -> prev > 0 ? prev - 1 : prev) < 0) {
        doRelease();
      }
    }
  }

  public boolean set(int index, long value) {
    // check'n'update the state - if it is non-negative, increment the counter and proceed,
    // otherwise bail out
    if (state.updateAndGet(prev -> prev >= 0 ? prev + 1 : prev) < 0) {
      // bail out if this instance was already released
      return false;
    }
    try {
      PositionDecoder.Coordinates decoded =
          positionDecoder.decode(index * 8, bufferBoundaryMap, bufferInitSlot + 1);
      if (decoded != null) {
        return buffers[decoded.slot].putLong(decoded.index, value);
      }
      return false;
    } finally {
      // check'n'update the state - if it is negative before update, perform release, otherwise just
      // decrement the counter
      if (state.getAndUpdate(prev -> prev > 0 ? prev - 1 : prev) < 0) {
        doRelease();
      }
    }
  }

  public long get(int index) {
    // check'n'update the state - if it is non-negative, increment the counter and proceed,
    // otherwise bail out
    if (state.updateAndGet(prev -> prev >= 0 ? prev + 1 : prev) < 0) {
      // bail out if this instance was already released
      return Long.MIN_VALUE;
    }
    try {
      PositionDecoder.Coordinates decoded =
          positionDecoder.decode(index * 8, bufferBoundaryMap, bufferInitSlot + 1);
      if (decoded != null) {
        return buffers[decoded.slot].getLong(decoded.index);
      }
      return Long.MIN_VALUE;
    } finally {
      // check'n'update the state - if it is negative before update, perform release, otherwise just
      // decrement the counter
      if (state.getAndUpdate(prev -> prev > 0 ? prev - 1 : prev) < 0) {
        doRelease();
      }
    }
  }

  public int size() {
    // check'n'update the state - if it is non-negative, increment the counter and proceed,
    // otherwise bail out
    if (state.updateAndGet(prev -> prev >= 0 ? prev + 1 : prev) < 0) {
      // bail out if this instance was already released
      return 0;
    }
    try {
      return size;
    } finally {
      // check'n'update the state - if it is negative before update, perform release, otherwise just
      // decrement the counter
      if (state.getAndUpdate(prev -> prev > 0 ? prev - 1 : prev) < 0) {
        doRelease();
      }
    }
  }

  public void release() {
    if (state.getAndUpdate(prev -> prev > 0 ? -1 * prev : prev) == 0) {
      doRelease();
    }
  }

  private void doRelease() {
    for (int i = 0; i < buffers.length; i++) {
      AllocatedBuffer buffer = buffers[i];
      if (buffer != null) {
        buffer.release();
      }
    }
    // clear out the buffer slots to allow the most of the retained data to be GCed
    // do not clear up the buffers contents as they still may be in use by an iterator instance
    buffers = null;
    bufferBoundaryMap = null;
    bufferInitSlot = -1;
    size = 0;
    state.set(-1);
  }

  public LongIterator iterator() {
    return new LongIteratorImpl();
  }
}
