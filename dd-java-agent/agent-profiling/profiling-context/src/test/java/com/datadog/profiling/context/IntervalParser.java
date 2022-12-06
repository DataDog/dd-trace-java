package com.datadog.profiling.context;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public final class IntervalParser {
  public static final class Interval {
    public final long threadId;
    public final long from;
    public final long till;

    public Interval(long threadId, long from, long till) {
      this.threadId = threadId;
      this.from = from;
      this.till = till;
    }
  }

  private static class GroupVarintIterator {
    private final ByteBuffer dataBuffer;
    private final ByteBuffer bitmapBuffer;

    private int maskIndex = 0;
    private int maskOffset = 0;

    GroupVarintIterator(ByteBuffer dataBuffer) {
      int index = dataBuffer.getInt();
      this.dataBuffer = dataBuffer.slice();
      this.bitmapBuffer = slice(dataBuffer, index);
    }

    private static ByteBuffer slice(ByteBuffer src, int index) {
      /*
      ByteBuffer.slice(index, size) is available only from JDK 13
      We need to emulate that by storing current position, setting the position to index, slicing and restoring the original position
       */
      int currentPos = src.position();
      try {
        src.position(index);
        return src.slice();
      } finally {
        src.position(currentPos);
      }
    }

    boolean hasNext() {
      return dataBuffer.position() < dataBuffer.capacity() && maskIndex < bitmapBuffer.capacity();
    }

    long next() {
      int mask = bitmapBuffer.getInt(maskIndex);
      int size = ((mask >>> (29 - maskOffset)) & 0x7) + 1;
      if ((maskOffset += 3) > 21) {
        maskIndex += 3;
        maskOffset = 0;
      }
      long value = 0L;
      for (int i = 0; i < size; i++) {
        int item = (int) dataBuffer.get() & 0xff;
        value = (value << 8) + item;
      }
      return value;
    }
  }

  public List<Interval> parseIntervals(byte[] intervalData) {
    List<Interval> result = new ArrayList<>();

    parseIntervals(
        intervalData,
        interval -> {
          result.add(interval);
          return true;
        });
    return result;
  }

  public interface IntervalConsumer {
    boolean accept(Interval interval);
  }

  public void parseIntervals(byte[] intervalData, IntervalConsumer consumer) {
    ByteBuffer buffer = ByteBuffer.wrap(intervalData);
    boolean truncated = buffer.get() != 0;
    int chunkDataOffset = buffer.getInt();
    long timestampNanos = getVarint(buffer);
    long frequencyMultiplier = getVarint(buffer);
    int numThreads = (int) getVarint(buffer);

    double frequency = frequencyMultiplier / 1000d;

    ByteBuffer dataChunk =
        ByteBuffer.wrap(intervalData, chunkDataOffset, intervalData.length - chunkDataOffset)
            .slice();

    GroupVarintIterator iterator = new GroupVarintIterator(dataChunk);
    for (int thread = 0; thread < numThreads; thread++) {
      long previousTicks = 0;
      long threadId = getVarint(buffer);
      int intervals = (int) getVarint(buffer);
      for (int interval = 0; interval < intervals; interval++) {
        if (iterator.hasNext()) {
          long startTsDelta = iterator.next();
          long endTsDelta = iterator.next();
          long startTs = previousTicks + startTsDelta;
          long endTs = startTs + endTsDelta;
          if (!consumer.accept(
              new Interval(
                  threadId,
                  (timestampNanos + (long) (startTs / frequency)),
                  (timestampNanos + (long) (endTs / frequency))))) {
            // consumer not interested in the rest of the data
            return;
          }
          previousTicks = endTs;
        } else {
          throw new IllegalStateException();
        }
      }
    }
  }

  private long getVarint(ByteBuffer buffer) {
    long ret = 0;
    for (int i = 0; i < 8; i++) {
      byte b = buffer.get();
      ret += (long) ((b & 0x7FL)) << (7 * i);
      if (b >= 0) {
        return ret;
      }
    }
    return ret + ((buffer.get() & 0xFFL) << 56);
  }
}
