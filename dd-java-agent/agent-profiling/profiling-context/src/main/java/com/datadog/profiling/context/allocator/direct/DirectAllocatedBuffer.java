package com.datadog.profiling.context.allocator.direct;

import com.datadog.profiling.context.LongIterator;
import com.datadog.profiling.context.PositionDecoder;
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

  private final PositionDecoder positionDecoder = PositionDecoder.getInstance();

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
    Arrays.fill(chunks, null);
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
      PositionDecoder.Coordinates coordinates = positionDecoder.decode(pos, chunkBoundaryMap);

      if (coordinates.slot < chunks.length) {
        chunks[coordinates.slot].buffer.putLong(coordinates.index, value);
        return true;
      }
    }
    return false;
  }

  @Override
  public long getLong(int pos) {
    if (pos + 8 <= capacity) {
      PositionDecoder.Coordinates coordinates = positionDecoder.decode(pos, chunkBoundaryMap);

      return chunks[coordinates.slot].buffer.getLong(coordinates.index);
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
        if (currentBuffer.limit() != 0) {
          if (chunkReadIndex <= chunkWriteIndex) {
            if (valueReadIndex >= currentBuffer.limit()) {
              chunkReadIndex++;
              valueReadIndex = 0;
              if (chunkReadIndex < chunks.length) {
                currentBuffer = (ByteBuffer) chunks[chunkReadIndex].buffer.duplicate().flip();
                if (currentBuffer.limit() != 0) {
                  computedHasNext = 1;
                  return true;
                }
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
        long value = currentBuffer.getLong();
        valueReadIndex += 8;
        readBytes += 8;
        computedHasNext = -1;
        return value;
      }
    };
  }
}
