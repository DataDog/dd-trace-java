package com.datadog.profiling.exceptions;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * A streaming (non-remembering) sampler.
 *
 * <p>The sampler attempts to generate at most N samples per fixed time window in randomized
 * fashion. It is also using constant updates of the estimated event set per window such that it can
 * vary the expected sampling interval (how many events are between two samples in average) to cover
 * the events within one window by approximately the number of requested samples per window. Due to
 * all the numbers being just estimates the actual number of samples may vary slightly (the tests
 * show the variability being under 10%) and it must be understood that the expected number of
 * samples per window is not a hard/precise limit.
 */
class StreamingSampler {
  /*
   * Exponential Moving Average (EMA) last element weight.
   * Check out papers about using EMA for streaming data - eg.
   * https://nestedsoftware.com/2018/04/04/exponential-moving-average-on-streaming-data-4hhl.24876.html
   */
  private static final double EMA_ALPHA = 0.6d;

  private final Supplier<Long> tsProvider;
  private final AtomicLong testCounter = new AtomicLong(0L);
  private final long windowDurationNs;
  private final int samplesPerWindow;

  /*
   * The following two fields are accessed from the synchronized block only so they don't need to be volatile.
   */
  private long thisWindowTs;
  private double intervalEma;

  /*
   * And these two fields are accessed also outside of the synchronized block so they need to be volatile to ensure
   * visibility.
   */
  private volatile long nextSample = -1L;
  private volatile long nextWindowTs;

  /**
   * Create a new sampler instance
   *
   * @param windowDuration     the sampling window duration
   * @param samplesPerWindow   the maximum number of samples in the sampling window
   * @param initialInterval    the initial sampling interval (number of events between two samples)
   * @param tsProvider         timestamp provider
   */
  StreamingSampler(
    Duration windowDuration,
    int samplesPerWindow,
    int initialInterval,
    Supplier<Long> tsProvider) {
    this.tsProvider = tsProvider;
    nextSample = getNextSample(initialInterval);
    windowDurationNs = windowDuration.toNanos();
    thisWindowTs = tsProvider.get();
    nextWindowTs = thisWindowTs + windowDurationNs;
    this.samplesPerWindow = samplesPerWindow;
    this.intervalEma = initialInterval;
  }

  /**
   * Create a new sampler instance
   *
   * @param windowDuration   the sampling window duration
   * @param samplesPerWindow the maximum number of samples in the sampling window
   * @param initialInterval  the initial sampling interval (number of events between two samples)
   */
  StreamingSampler(Duration windowDuration,
                   int samplesPerWindow,
                   int initialInterval) {
    this(windowDuration, samplesPerWindow, initialInterval, System::nanoTime);
  }

  /**
   * Provides binary answer whether the current event is to be sampled
   *
   * @return {@literal true} if the event should be sampled
   */
  boolean sample() {
    long ts = tsProvider.get();
    // check whether the current window is expired
    boolean isExpired = ts >= nextWindowTs;

    long test = testCounter.incrementAndGet();
    // check for the sampling interval to have elapsed
    boolean isSampled = test >= nextSample;
    if (isSampled || isExpired) {
      /*
       * Going to modify the shared state here.
       * Need a fully synchronized block - relaxed locking via try-lock leads to severe undersampling in
       * multi-threaded scenario.
       * This block is hit only on sample or when the window has expired. That means that it should be infrequent
       * enough to make the contention very improbable.
       */
      synchronized (this) {
        /*
         * Need to retest the expiration and sampling since they may have been changed by other threads
         * since the previous check.
         */
        ts = tsProvider.get();
        test = testCounter.get();

        isSampled = test >= nextSample;
        isExpired = ts >= nextWindowTs;
        if (!isSampled && !isExpired) {
          return false;
        }

        /*
         * Get the estimated event set size per the sampling window given the up-to-now incoming rate and the window duration.
         */
        double estimatedSetSize = ((double) test / (ts - thisWindowTs)) * windowDurationNs;

        /*
         * Derive the desired sampling interval as such the expected number of samples can cover the estimated size
         * in one sampling window.
         */
        double interval = estimatedSetSize / samplesPerWindow;
        if (!Double.isNaN(intervalEma)) {
          // calculate the approximation of exponential moving average (EMA) of the sampling interval
          interval = intervalEma + (EMA_ALPHA * (interval - intervalEma));
        }
        intervalEma = interval;

        if (isExpired) {
          // when a window is expired we need to update the new window timestamps and reset the test counter
          nextWindowTs = ts + windowDurationNs;
          thisWindowTs = ts;
          /*
           * Be nice in a multithreaded env and do not unconditionally set to 0. This means that if other threads
           * managed to increment this counter while this one was executing this synchronized block we want to
           * subtract the last known value of that counter obtained *after* entering the synchronized block.
           */
          test = testCounter.addAndGet(-test);
        }
        // calculate the index of the next expected sample based on the interval EMA and some random jitter
        nextSample = test + getNextSample(intervalEma);

        return isSampled;
      }
    }
    return false;
  }

  private static long getNextSample(double interval) {
    // jitter the sampling interval to increase sample randomness
    return Math.max(Math.round(interval + ThreadLocalRandom.current().nextGaussian() * interval * 0.1), 1);
  }
}
