package com.datadog.profiling.exceptions;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

public class StreamingSampler {
  private static final int SCALE = 10_000;

  private final long slidingWindowDuration;
  private final int slidingWindowSize;

  private final Supplier<Long> timeStampSupplier;
  private final LongAdder hitCounter = new LongAdder();
  private final AtomicLong sampleCounter = new AtomicLong(0L);

  private final AtomicLong slidingWindowEndTsRef;
  private final AtomicInteger thresholdRef;

  public StreamingSampler(
      final long slidingWindowDuration,
      final TimeUnit slidingWindowUnit,
      final int slidingWindowSize) {
    this(slidingWindowDuration, slidingWindowUnit, slidingWindowSize, System::nanoTime);
  }

  StreamingSampler(
      final long slidingWindowDuration,
      final TimeUnit slidingWindowUnit,
      final int slidingWindowSize,
      final Supplier<Long> timeStampSupplier) {
    this.slidingWindowDuration =
        TimeUnit.NANOSECONDS.convert(slidingWindowDuration, slidingWindowUnit);
    this.slidingWindowSize = slidingWindowSize;
    slidingWindowEndTsRef = new AtomicLong(timeStampSupplier.get() + this.slidingWindowDuration);
    thresholdRef = new AtomicInteger(Math.round((1f / slidingWindowSize) * SCALE));
    this.timeStampSupplier = timeStampSupplier;
  }

  public boolean sample() {
    hitCounter.increment();
    final int threshold = thresholdRef.get();
    boolean result = false;
    if (test(threshold)) {
      final long samples = sampleCounter.incrementAndGet();
      thresholdRef.getAndUpdate(v -> v == threshold ? getNextThreshold(samples, v) : v);
      result = true;
    }
    final long ts = timeStampSupplier.get();
    final long tsEnd = slidingWindowEndTsRef.get();
    if (ts >= tsEnd) {
      final long hits = hitCounter.sumThenReset();
      thresholdRef.getAndUpdate(
          v ->
              v == threshold
                  ? Math.round((1 - Math.min(((float) slidingWindowSize / hits), 1f)) * SCALE)
                  : v);
      if (slidingWindowEndTsRef.compareAndSet(tsEnd, ts + slidingWindowDuration)) {
        sampleCounter.set(0);
      }
    }
    return result;
  }

  private int getNextThreshold(final long samples, final int currentThreshold) {
    final long diff = (slidingWindowSize - samples) + 1;
    if (diff == 0) {
      return SCALE;
    }
    return Math.max(Math.round((float) SCALE / diff), currentThreshold);
  }

  private boolean test(final int value) {
    return ThreadLocalRandom.current().nextInt(SCALE) >= value;
  }
}
