package com.datadog.profiling.context.allocator.direct;

import com.datadog.profiling.context.LongIterator;
import com.datadog.profiling.context.allocator.AllocatedBuffer;
import java.nio.ByteBuffer;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DirectAllocatedBuffer implements AllocatedBuffer {
  private static final Logger log = LoggerFactory.getLogger(DirectAllocatedBuffer.class);

  private final Chunk[] chunks;
  private final int[] chunkBoundaryMap;
  private final int capacity;
  private int chunkWriteIndex = 0;
  private int valueWriteIndex = 0;

  DirectAllocatedBuffer(int bufferSize, int chunkSize, Chunk... chunks) {
    this.chunks = chunks;
    this.chunkBoundaryMap = new int[chunks.length];
    this.capacity = bufferSize;

    int boundary = 0;
    for (int i = 0; i < chunks.length; i++) {
      boundary += chunkSize * chunks[i].getWeight();
      chunkBoundaryMap[i] = boundary - 1;
    }
  }

  @Override
  public void release() {
    for (Chunk chunk : chunks) {
      chunk.release();
    }
  }

  @Override
  public int capacity() {
    return capacity;
  }

  @Override
  public boolean putLong(long value) {
    if (valueWriteIndex == chunks[chunkWriteIndex].buffer.limit()) {
      valueWriteIndex = 0;
      if (++chunkWriteIndex == chunks.length) {
        return false;
      }
    }
    chunks[chunkWriteIndex].buffer.putLong(value);
    valueWriteIndex += 8;
    return true;
  }

  @Override
  public boolean putLong(int pos, long value) {
    if (pos + 8 <= capacity) {
      int[] coordinates = decode(pos);

      int chunkSlot = coordinates[0];
      int chunkOffset = coordinates[1];
      if (chunkSlot < chunks.length) {
        try {
          chunks[chunkSlot].buffer.putLong(chunkOffset, value);
          return true;
        } catch (IndexOutOfBoundsException e) {
          log.error(
              "Buffer access error: position={}, capacity={}, chunks={}, chunkSlot={}, chunkOffset={}, buffer.capacity={}, buffer.limit={}",
              pos,
              capacity,
              chunks.length,
              chunkSlot,
              chunkOffset,
              chunks[chunkSlot].buffer.capacity(),
              chunks[chunkSlot].buffer.limit());
        }
      }
    }
    return false;
  }

  @Override
  public long getLong(int pos) {
    if (pos + 8 <= capacity) {
      int[] coordinates = decode(pos);

      int chunkSlot = coordinates[0];
      int chunkOffset = coordinates[1];

      return chunks[chunkSlot].buffer.getLong(chunkOffset);
    }
    return Long.MIN_VALUE;
  }

  @Override
  public LongIterator iterator() {
    return new LongIterator() {
      int valueReadIndex = 0;
      int chunkReadIndex = -1;
      int readBytes = 0;
      byte computedHasNext = -1;
      ByteBuffer currentBuffer = null;

      @Override
      public boolean hasNext() {
        if (computedHasNext > -1) {
          return computedHasNext == 1;
        }

        if (chunkReadIndex < 0) {
          chunkReadIndex = 0;
          currentBuffer = (ByteBuffer) chunks[chunkReadIndex].buffer.duplicate().flip();
        }
        if (chunkWriteIndex >= 0) {
          if (chunkReadIndex <= chunkWriteIndex) {
            if (valueReadIndex >= currentBuffer.limit()) {
              chunkReadIndex++;
              valueReadIndex = 0;
              if (chunkReadIndex < chunks.length) {
                currentBuffer = (ByteBuffer) chunks[chunkReadIndex].buffer.duplicate().flip();
                computedHasNext = 1;
                return true;
              }
            } else {
              computedHasNext = 1;
              return true;
            }
          }
        }
        currentBuffer = null;
        computedHasNext = 0;
        return false;
      }

      @Override
      public long next() {
        try {
          long value = currentBuffer.getLong();
          valueReadIndex += 8;
          readBytes += 8;
          computedHasNext = -1;
          return value;
        } catch (Throwable t) {
          throw t;
        }
      }
    };
  }

  private int[] decode(int pos) {
    // shortcut for an index falling within the first slot
    if (pos <= chunkBoundaryMap[0]) {
      return new int[] {0, pos};
    }

    // shortcut to linear search for a small number of slots in use
    if (chunkBoundaryMap.length < 5) {
      int slot = 0;
      while (slot < chunkBoundaryMap.length && chunkBoundaryMap[slot] < pos) {
        slot++;
      }
      if (slot < chunkBoundaryMap.length) {
        return slot > 0 ? new int[] {slot, pos - chunkBoundaryMap[slot - 1]} : new int[] {slot, pos};
      }
      return null;
    }

    // use binary search
    int slot = Arrays.binarySearch(chunkBoundaryMap, pos);
    if (slot > 0) {
      return new int[] {slot, pos - chunkBoundaryMap[slot - 1] - 1};
    } else if (slot == 0) {
      return new int[] {slot, pos};
    } else {
      slot = 1 - slot;
      if (slot < chunkBoundaryMap.length) {
        return new int[] {slot, pos - chunkBoundaryMap[slot - 1] - 1};
      }
      return null;
    }
  }
}
