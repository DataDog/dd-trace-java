package datadog.trace.core;

import datadog.trace.api.Config;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Consumer;

public class RunningSpansBuffer {
  private final RingBuffer runningSpans = new RingBuffer();
  private final LongRunningTracker runningTracker = new LongRunningTracker();
  private final CoreTracer tracer;

  private long lastFlushMilli = 0;

  public RunningSpansBuffer(
      CoreTracer tracer,
      Config config) { // TODO use config and flush missedAdd stats in health check class
    this.tracer = tracer;
  }

  public void add(DDSpan s) {
    runningSpans.add(s);
  }

  public void flushRunningSpans(long nowMilli) {
    if (nowMilli - lastFlushMilli < TimeUnit.SECONDS.toMillis(1)) {
      return;
    }
    runningTracker.flushAndCompact(nowMilli);
    runningSpans.flush(runningTracker::add);
    lastFlushMilli = nowMilli;
  }

  /**
   * RingBuffer is a lockless data struct with multiple threads writing and a single thread reading.
   *
   * <p>Writes and flushes in the buffer start at the same position and move to the right by
   * increments of 1 flush passes through at most newAddition to the buffer When the buffer is full
   * new spans are not tracked anymore
   */
  private static class RingBuffer {
    private final int ringSize = 10000;
    private final AtomicReferenceArray<DDSpan> ring = new AtomicReferenceArray<>(ringSize);
    private final AtomicInteger writeIndex = new AtomicInteger(-1);
    private final AtomicInteger missedAdd = new AtomicInteger();
    private final AtomicInteger newAdditions = new AtomicInteger();
    private int readIndex;

    // add is called by any thread generating a span
    private void add(DDSpan s) {
      int index = writeIndex.incrementAndGet();
      // keep the writeIndex value in [0, k*ringSize[ with k*ringSize << maxInt64. Hitting the limit
      // would require maxInt64 threads incrementing writeIndex and not passing below
      if (index > ringSize && index % ringSize == 0) {
        this.writeIndex.addAndGet(-ringSize);
      }
      boolean success = this.ring.compareAndSet(index % ringSize, null, s);
      if (!success) {
        this.missedAdd.incrementAndGet();
        return;
      }
      this.newAdditions.incrementAndGet();
    }

    // flush is called by the PendingTraceBuffer thread.
    // It iterates through the array until the number of flushed span
    // is equal to the number of new spans added since the last iteration.
    // To avoid fragmentation of the array the next flush starts on the first empty slot
    // seen by the previous iteration.
    // Iterations are on average in O(min(newSpans, ringSize))
    public void flush(Consumer<DDSpan> sendToTracker) {
      int newSpans = newAdditions.getAndSet(0);
      int voidedSpans = 0;
      int firstEmptySlot = -1;
      for (int i = 0; i < ringSize; i++) {
        if (newSpans == voidedSpans) {
          if (firstEmptySlot > -1) {
            readIndex = firstEmptySlot;
          }
          return;
        }
        DDSpan spanRef = ring.getAndSet(readIndex, null);
        readIndex++;
        if (readIndex == ringSize) {
          readIndex -= ringSize;
        }
        if (spanRef == null) {
          if (firstEmptySlot == -1) {
            firstEmptySlot = readIndex - 1;
          }
          continue;
        }
        voidedSpans++;
        sendToTracker.accept(spanRef);
      }
    }
  }

  /**
   * LongRunningTracker tracks running spans, evicting them if completed and writing them
   * periodically
   *
   * <p>Only called by the PendingTraceBuffer thread.
   */
  private class LongRunningTracker {
    private final int maxTrackedSpans = 10000;
    private final int flushPeriodMilli = (int) TimeUnit.SECONDS.toMillis(5); // todo get from config
    private final DDSpan[] spansArray = new DDSpan[maxTrackedSpans];
    private int lastElementIndex = -1;
    private int missedAdd = 0;

    public void add(DDSpan span) {
      if (span == null || span.isFinished()) {
        return;
      }
      if (lastElementIndex == maxTrackedSpans - 1) {
        missedAdd++;
        return;
      }
      lastElementIndex++;
      spansArray[lastElementIndex] = span;
    }

    public boolean shouldFlush(long nowMilli, DDSpan span) {
      long version = (long) span.context().getLongRunningVersion();
      long nextFlushTime = span.getStartTime() / 1000000 + (version + 1) * flushPeriodMilli;
      return nowMilli > nextFlushTime;
    }

    public void flushAndCompact(long nowMilli) {
      int i = 0;
      while (i <= lastElementIndex) {
        DDSpan span = spansArray[i];
        if (span == null || span.isFinished()) {
          cleanSlot(i);
          continue;
        }
        if (!shouldFlush(nowMilli, span)) {
          i++;
          continue;
        }
        long spanStartMilli = span.getStartTime() / 1000000;
        if (nowMilli - spanStartMilli > TimeUnit.HOURS.toMillis(12)) {
          cleanSlot(i);
          continue;
        }
        writeLongRunning(nowMilli, spanStartMilli, span);
        i++;
      }
    }

    // TODO get guidance on best API to, gave a shot below
    // - not risk race conditions
    // - set _dd.partial_version in v04/v05 protocol on long running spans
    // - set _dd.was_longrunning to 1 in v04/v05 protocol on the completed long running span
    // - set duration to flush time on the flushed long running span
    private void writeLongRunning(long nowMilli, long startTimeMilli, DDSpan span) {
      int version = (int) (nowMilli - startTimeMilli) / flushPeriodMilli;
      span.context().setRunningVersion(version);
      DDSpan longRunningSpan =
          span.cloneLongRunning(nowMilli * 1000000); // todo use tracer time source
      if (longRunningSpan == null) {
        return;
      }
      tracer.writer.write(
          Collections.singletonList(
              longRunningSpan)); // bypass metrics computation and spans health stats
    }

    private void cleanSlot(int index) {
      spansArray[index] = spansArray[lastElementIndex];
      spansArray[lastElementIndex] = null;
      lastElementIndex--;
    }
  }
}
