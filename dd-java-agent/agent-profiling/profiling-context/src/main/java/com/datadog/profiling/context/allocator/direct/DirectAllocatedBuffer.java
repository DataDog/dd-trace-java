package com.datadog.profiling.context.allocator.direct;

import com.datadog.profiling.context.LongIterator;
import com.datadog.profiling.context.allocator.AllocatedBuffer;

import java.nio.ByteBuffer;

public final class DirectAllocatedBuffer implements AllocatedBuffer {
  private final Chunk[] chunks;
  private final int capacity;
  private int chunkWriteIndex = 0;
  private int valueWriteIndex = 0;

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
    return true;
  }

  @Override
  public LongIterator iterator() {
    return new LongIterator() {
      int valueReadIndex = 0;
      int chunkReadIndex = -1;
      ByteBuffer currentBuffer = null;

      @Override
      public boolean hasNext() {
        while (chunkWriteIndex >= 0 && valueReadIndex < valueWriteIndex && chunkReadIndex <= chunkWriteIndex) {
          if (chunkReadIndex < 0) {
            chunkReadIndex = 0;
            currentBuffer = (ByteBuffer) chunks[chunkReadIndex].buffer.duplicate().flip();
          }
          if (valueReadIndex >= currentBuffer.limit()) {
            chunkReadIndex++;
            if (chunkReadIndex <= chunkWriteIndex) {
              valueReadIndex = 0;
              currentBuffer = (ByteBuffer) chunks[chunkReadIndex].buffer.duplicate().flip();
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
          valueReadIndex += 8;
          return value;
        } catch (Throwable t) {
          System.out.println("===> " + valueReadIndex);
          throw t;
        }
      }
    };
  }
}
