package com.datadog.profiling.context.allocator.direct;

import com.datadog.profiling.context.LongIterator;
import com.datadog.profiling.context.allocator.AllocatedBuffer;
import java.nio.ByteBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DirectAllocatedBuffer implements AllocatedBuffer {
  private static final Logger log = LoggerFactory.getLogger(DirectAllocatedBuffer.class);

  private final Chunk[] chunks;
  private final int capacity;
  private int chunkWriteIndex = 0;
  private int valueWriteIndex = 0;
  private int valueWriteLimit = 0;

  DirectAllocatedBuffer(int bufferSize, Chunk... chunks) {
    this.chunks = chunks;
    this.capacity = bufferSize;
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
    valueWriteLimit += 8;
    return true;
  }

  @Override
  public boolean putLong(int pos, long value) {
    if (pos + 8 <= capacity) {
      int chunkPos = 0;
      int chunkOffset = 0;
      int runningPos = 0;
      while (chunkPos < chunks.length) {
        int bufferSize = chunks[chunkPos].buffer.limit();
        if (runningPos + bufferSize > pos) {
          chunkOffset = pos - runningPos;
          break;
        }
        runningPos += bufferSize;
        chunkPos++;
      }
      if (chunkPos < chunks.length) {
        try {
          chunks[chunkPos].buffer.putLong(chunkOffset, value);
          return true;
        } catch (IndexOutOfBoundsException e) {
          log.error(
              "Buffer access error: position={}, capacity={}, chunks={}, chunkPos={}, chunkOffset={}, buffer.capacity={}, buffer.limit={}",
              pos,
              capacity,
              chunks.length,
              chunkPos,
              chunkOffset,
              chunks[chunkPos].buffer.capacity(),
              chunks[chunkPos].buffer.limit());
        }
      }
    }
    return false;
  }

  @Override
  public long getLong(int pos) {
    if (pos + 8 <= capacity) {
      int chunkCapacity = capacity / chunks.length;

      int chunkPos = pos / chunkCapacity;
      int chunkOffset = pos % chunkCapacity;
      return chunks[chunkPos].buffer.getLong(chunkOffset);
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
}
