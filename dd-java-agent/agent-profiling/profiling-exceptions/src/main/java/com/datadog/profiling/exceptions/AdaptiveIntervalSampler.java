package com.datadog.profiling.exceptions;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public final class AdaptiveIntervalSampler {
  private final double stdDev;
  private final String id;
  private final int minInterval;
  private long interval;
  private final long timeWindowNs;
  private final long maxSamples;
  private final long targetSamples;
  private volatile long counterTop = 0L;
  private final AtomicLong counter;
  private long ts = System.nanoTime();
  private long sampleCounter = 0L;
  private final int dummyMultiplier = 1;

  public AdaptiveIntervalSampler(final String id, final int minInterval, final long timeWindowMs, final long maxSamples) {
    this(id, minInterval, 50, timeWindowMs, maxSamples);
  }

  public AdaptiveIntervalSampler(
    final String id, final int minInterval, final int stdDevPercent, final long timeWindowMs, final long maxSamples) {
    this.id = id;
    this.minInterval = minInterval;
    stdDev = Math.max(Math.min(stdDevPercent, 100), 1) / 100d;
    interval = minInterval;
    timeWindowNs = TimeUnit.NANOSECONDS.convert(timeWindowMs, TimeUnit.MILLISECONDS);
    this.maxSamples = maxSamples;
    // for small minimal intervals the sampler can overshoot significantly; trying to compensate for
    // that here
    targetSamples = Math.round(maxSamples / (Math.max(1, 3 - Math.log10(minInterval))));
    counterTop = expectedHits(interval);
    counter = new AtomicLong(counterTop);
  }

  private long expectedHits(final long interval) {
    return Math.max(
      Math.round(ThreadLocalRandom.current().nextGaussian() * interval * stdDev) + interval, 1);
  }

  public void reset() {
    log.debug("Sampler reset [id: {}]", id);
    interval = minInterval;
    sampleCounter = 0L;
    // write to volatile 'counterTop' to force the visibility of change to 'interval'
    counterTop = counterTop * dummyMultiplier;
  }

  public boolean sample() {
    /*
     * All changes done by other threads before writing to 'counterTop' will be visible after this
     * line
     */
    long currentTop = counterTop;
    if (sampleCounter >= maxSamples) {
      // hit the hard limit; no more samples
      return false;
    }
    final long currentCnt = counter.decrementAndGet();

    if (currentCnt % currentTop == 0L) {
      final long ts1 = System.nanoTime();
      // all changes done by other threads before writing to 'ts' will be visible after this line
      final long tDiff = ts1 - ts;
      final double projectedSamplesCnt = timeWindowNs / (double) tDiff;
      final double intervalScale = projectedSamplesCnt / maxSamples;
      final long newInterval = Math.max(Math.round(interval * intervalScale), minInterval);
      // calculate interval diff in a way it does not swing wildly
      final long intervalDiff =
        newInterval == interval
          ? 0
          : Math.round(
          Math.log(Math.abs(newInterval - interval)) * (newInterval < interval ? -1 : 1));
      interval = interval + intervalDiff;
      // write to counterTop makes all previous changes to shared data visible after counterTop is
      // read by other threads
      currentTop = expectedHits(interval);
      counter.addAndGet(counterTop);
      sampleCounter++;
      ts = ts1;
      // write to 'counterTop' makes all previous changes to shared data visible after ts is read by other
      // threads
      counterTop = currentTop;

      return true;
    }
    return false;
  }

  public String getId() {
    return id;
  }
}
