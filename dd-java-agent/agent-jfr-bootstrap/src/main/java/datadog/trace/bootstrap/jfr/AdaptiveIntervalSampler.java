package datadog.trace.bootstrap.jfr;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class AdaptiveIntervalSampler {
  private final double stdDev;
  private final String id;
  private final int minInterval;
  private long interval;
  private final long timeWindowNs;
  private final long maxSamples;
  private volatile long ts = System.nanoTime();
  private volatile long counterTop = 0L;
  private final AtomicLong counter;
  private final int dummyMultiplier = 1;

  public AdaptiveIntervalSampler(String id, int minInterval, long timeWindowMs, long maxSamples) {
    this(id, minInterval, 50, timeWindowMs, maxSamples);
  }

  public AdaptiveIntervalSampler(
      String id, int minInterval, int stdDevPercent, long timeWindowMs, long maxSamples) {
    this.id = id;
    this.minInterval = minInterval;
    this.stdDev = Math.max(Math.min(stdDevPercent, 100), 1) / 100d;
    this.interval = minInterval;
    this.timeWindowNs = TimeUnit.NANOSECONDS.convert(timeWindowMs, TimeUnit.MILLISECONDS);
    // for small minimal intervals the sampler can overshoot significantly; trying to compensate for
    // that here
    this.maxSamples = Math.round(maxSamples / (Math.max(1, 4 - Math.log10(minInterval))));
    this.counterTop = expectedHits(interval);
    this.counter = new AtomicLong(counterTop);
  }

  private long expectedHits(long interval) {
    return Math.max(
        Math.round(ThreadLocalRandom.current().nextGaussian() * interval * stdDev) + interval, 1);
  }

  public void reset() {
    log.debug("Sampler reset [id: {}]", id);
    interval = minInterval;
    // write to volatile 'ts' to force the visibility of change to 'interval'
    ts = ts * dummyMultiplier;
  }

  public boolean sample() {
    long currentCnt = counter.decrementAndGet();

    // all changes done by other threads before writing to 'counterTop' will be visible after this
    // line
    if (currentCnt % counterTop == 0L) {
      long ts1 = System.nanoTime();
      // all changes done by other threads before writing to 'ts' will be visible after this line
      long tDiff = ts1 - ts;
      double projectedSamplesCnt = timeWindowNs / (double) tDiff;
      double intervalScale = projectedSamplesCnt / maxSamples;
      long newInterval = Math.max(Math.round(interval * intervalScale), minInterval);
      // calculate interval diff in a way it does not swing wildly
      long intervalDiff =
          newInterval == interval
              ? 0
              : Math.round(
                  Math.log(Math.abs(newInterval - interval)) * (newInterval < interval ? -1 : 1));
      interval = interval + intervalDiff;
      // write to counterTop makes all previous changes to shared data visible after counterTop is
      // read by other threads
      counterTop = expectedHits(interval);
      counter.addAndGet(counterTop);
      // write to ts makes all previous changes to shared data visible after ts is read by other
      // threads
      ts = ts1;

      return true;
    }
    return false;
  }

  public String getId() {
    return id;
  }
}
