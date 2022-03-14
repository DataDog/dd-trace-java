package com.datadog.profiling.context;

import java.nio.ByteBuffer;

final class IntervalEncoder {
  private final ByteBuffer prologueBuffer;
  private final ByteBuffer dataChunkBuffer;
  private final ByteBuffer groupVarintMapBuffer;
  private final int threadCount;
  private int threadIndex = 0;

  private int maskPos = 0;
  private int maskOffset = 0;

  private boolean encoderFinished = false;
  private boolean threadInFlight = false;
  private final LEB128Support leb128Support = new LEB128Support();

  final class ThreadEncoder {
    private final byte[] elements = new byte[8];

    private long runningTimestamp;
    private final long threadId;
    private int intervals = 0;

    private boolean threadFinished = false;

    ThreadEncoder(long threadId) {
      this.threadId = threadId;
      this.runningTimestamp = 0;
    }

    void recordInterval(long from, long till) {
      if (threadFinished) {
        throw new IllegalStateException("Illegal state: threadFinished=" + threadFinished);
      }
      intervals++;
      putLongValue(from - runningTimestamp);
      putLongValue(till - from);
      runningTimestamp = till;
    }

    IntervalEncoder finish() {
      if (threadFinished) {
        throw new IllegalStateException("Illegal state: threadFinished=" + threadFinished);
      }
      leb128Support.putVarint(prologueBuffer, threadId);
      leb128Support.putVarint(prologueBuffer, intervals);
      threadInFlight = false;
      threadFinished = true;
      return IntervalEncoder.this;
    }

    private void putLongValue(long value) {
      int size = leb128Support.longSize(value);

      int base = size - 1;
      for (int i = 0; i < size; i++) {
        elements[base - i] = (byte) (value & 0xff);
        value = value >>> 8;
      }
      dataChunkBuffer.put(elements, 0, size);
      storeLongValueSize(size);
    }

    private void storeLongValueSize(int size) {
      groupVarintMapBuffer.position(maskPos);
      groupVarintMapBuffer.mark();
      int mask = groupVarintMapBuffer.getInt();

      // record the current length
      // need to do 'size - 1' to be able to squeeze the length into 3 bits -> 0 means size of 1
      // etc.
      mask = mask | (((size - 1) & 0x7) << (29 - maskOffset));
      // and rewrite the mask
      groupVarintMapBuffer.reset();
      groupVarintMapBuffer.putInt(mask);
      if ((maskOffset += 3) > 21) {
        maskOffset = 0;
        maskPos += 3;
      }
    }
  }

  IntervalEncoder(long timestamp, int threadCount, int maxSize) {
    this.threadCount = threadCount;

    this.prologueBuffer =
        ByteBuffer.allocate(
            leb128Support.varintSize(timestamp)
                + leb128Support.varintSize(threadCount)
                + threadCount * 18);
    this.dataChunkBuffer = ByteBuffer.allocate(maxSize * 8 + 4);
    this.groupVarintMapBuffer =
        ByteBuffer.allocate(leb128Support.align((int) (Math.ceil(maxSize / 8d) * 3), 4));

    prologueBuffer.putInt(0); // pre-allocate space for the datachunk offset
    dataChunkBuffer.putInt(0); // pre-allocate space for the group varint map offset
    leb128Support.putVarint(prologueBuffer, timestamp);
    leb128Support.putVarint(prologueBuffer, threadCount);
  }

  ThreadEncoder startThread(long threadId) {
    if (threadInFlight || threadIndex++ >= threadCount) {
      throw new IllegalStateException(
          "Illegal state: threadInFlight="
              + threadInFlight
              + ", threadIndex="
              + threadIndex
              + ", threadCount="
              + threadCount);
    }
    threadInFlight = true;
    return new ThreadEncoder(threadId);
  }

  ByteBuffer finish() {
    if (encoderFinished || threadInFlight) {
      throw new IllegalStateException(
          "Illegal state: encoderFinished="
              + encoderFinished
              + ", threadInFlight="
              + threadInFlight);
    }
    encoderFinished = true;
    ByteBuffer buffer =
        ByteBuffer.allocate(
            prologueBuffer.position()
                + dataChunkBuffer.position()
                + groupVarintMapBuffer.position());
    prologueBuffer.putInt(0, prologueBuffer.position()); // store the data chunk offset
    dataChunkBuffer.putInt(0, dataChunkBuffer.position()); // store the group varint bitmap offset
    // and now store the parts of the blob
    buffer.put((ByteBuffer) prologueBuffer.flip()); // prologue
    buffer.put((ByteBuffer) dataChunkBuffer.flip()); // data chunk
    buffer.put((ByteBuffer) groupVarintMapBuffer.flip()); // group varint bitmap
    return (ByteBuffer) buffer.flip();
  }
}
