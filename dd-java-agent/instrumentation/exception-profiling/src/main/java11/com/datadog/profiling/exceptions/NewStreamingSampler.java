package com.datadog.profiling.exceptions;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
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
class NewStreamingSampler {
  /*
   * Exponential Moving Average (EMA) last element weight.
   * Check out papers about using EMA for streaming data - eg.
   * https://nestedsoftware.com/2018/04/04/exponential-moving-average-on-streaming-data-4hhl.24876.html
   */
  private static final double EMA_ALPHA = 0.6d;

  private final Supplier<Long> tsProvider;
  private final long windowDurationNs;
  private final int samplesPerWindow;

  private final AtomicLong testCounter = new AtomicLong(0L);
  private final AtomicLong sampledCounter = new AtomicLong(0L);
  private final AtomicLong overshootCounter = new AtomicLong(0L);
  private final ReentrantLock endOfWindowLock = new ReentrantLock();
  private volatile double probability = 1d;
  private volatile double sigma = 0;
  private volatile long nextWindowTs;
  private volatile double totalCountRunningAverage = 0;

  /**
   * Create a new sampler instance
   *
   * @param windowDuration the sampling window duration
   * @param samplesPerWindow the maximum number of samples in the sampling window
   * @param initialInterval the initial sampling interval (number of events between two samples)
   * @param tsProvider timestamp provider
   */
  NewStreamingSampler(
      final Duration windowDuration,
      final int samplesPerWindow,
      final int initialInterval,
      final Supplier<Long> tsProvider) {
    this.tsProvider = tsProvider;
    windowDurationNs = windowDuration.toNanos();
    nextWindowTs = tsProvider.get() + windowDurationNs;
    this.samplesPerWindow = samplesPerWindow;
    // intervalEma = initialInterval;
  }

  /**
   * Create a new sampler instance
   *
   * @param windowDuration the sampling window duration
   * @param samplesPerWindow the maximum number of samples in the sampling window
   * @param initialInterval the initial sampling interval (number of events between two samples)
   */
  NewStreamingSampler(
      final Duration windowDuration, final int samplesPerWindow, final int initialInterval) {
    this(windowDuration, samplesPerWindow, initialInterval, System::nanoTime);
  }

  /**
   * Provides binary answer whether the current event is to be sampled
   *
   * @return {@literal true} if the event should be sampled
   */
  boolean sample() {
    boolean sampled = false;
    testCounter.incrementAndGet();
    final double z = 3.891; // 99.99%
    if (sampledCounter.get() <= samplesPerWindow + Math.ceil(z * sigma)) {
      if (ThreadLocalRandom.current().nextDouble() < probability) {
        sampledCounter.incrementAndGet();
        sampled = true;
      }
    } else {
      overshootCounter.incrementAndGet();
    }

    final long ts = tsProvider.get();
    final long extraDuration = ts - nextWindowTs;
    final boolean isExpired = extraDuration >= 0;

    if (isExpired) {
      if (endOfWindowLock.tryLock()) {
        try {
          final long totalCount = testCounter.getAndSet(0);
          final long sampledCount = sampledCounter.getAndSet(0);

          /*System.out.println(
          "!!!Ending window: got: "
              + sampledCount
              + " expected: "
              + samplesPerWindow
              + " + "
              + (z * sigma)
              + " extra duration: "
              + extraDuration
              + " probability: "
              + probability
              + " total count: "
              + totalCount
              + " overshoot count: "
              + overshootCounter.getAndSet(0));*/

          if (totalCountRunningAverage == 0) {
            totalCountRunningAverage = totalCount;
          } else {
            totalCountRunningAverage =
                (1 - EMA_ALPHA) * totalCountRunningAverage + EMA_ALPHA * totalCount;
          }

          if (totalCountRunningAverage <= samplesPerWindow) {
            probability = 1;
          } else {
            probability = Math.min(((double) samplesPerWindow) / totalCountRunningAverage, 1d);
          }

          // This whole sigma and z business it just a very crude way to estimate confidence
          // interval for binomial distribution. And then later use that confidence interval for a
          // reasonable amount of allowed overshoot.
          // This still biases us to undershoot and this (or probability above) calculation can be
          // improved/hacked.
          sigma = Math.sqrt(probability * (1 - probability) * totalCountRunningAverage) * 1.5;

          nextWindowTs = nextWindowTs + windowDurationNs;
        } finally {
          endOfWindowLock.unlock();
        }
      }
    }

    return sampled;
  }
}
