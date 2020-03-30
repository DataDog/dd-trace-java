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
class StreamingSampler {
  /*
   * Exponential Moving Average (EMA) last element weight.
   * Check out papers about using EMA for streaming data - eg.
   * https://nestedsoftware.com/2018/04/04/exponential-moving-average-on-streaming-data-4hhl.24876.html
   */
  private static final double EMA_ALPHA = 0.01d;

  /*
   * We keep a 'budget' by counting number of unused samples form last 10 windows
   * Exact value here should be on order of (but probably less than) size of the JFR chunk duration.
   */
  private static final int CARRIED_OVER_ARRAY_SIZE = 10;

  private final Supplier<Long> tsProvider;
  private final long windowDurationNs;
  private final int samplesPerWindow;

  private final AtomicLong testCounter = new AtomicLong(0L);
  private final AtomicLong sampledCounter = new AtomicLong(0L);
  private final AtomicLong overshootCounter = new AtomicLong(0L);
  private final ReentrantLock endOfWindowLock = new ReentrantLock();
  private volatile double probability = 1d;
  private final AtomicLong nextWindowTs = new AtomicLong(0);
  private volatile double totalCountRunningAverage = 0;
  private final long[] carriedOverSamples;
  private int carriedOverSampleIndex = 0;
  private final AtomicLong extraBudget = new AtomicLong(0);

  /**
   * Create a new sampler instance
   *
   * @param windowDuration the sampling window duration
   * @param samplesPerWindow the maximum number of samples in the sampling window
   * @param tsProvider timestamp provider
   */
  StreamingSampler(
      final Duration windowDuration, final int samplesPerWindow, final Supplier<Long> tsProvider) {
    this.tsProvider = tsProvider;
    windowDurationNs = windowDuration.toNanos();
    nextWindowTs.set(tsProvider.get() + windowDurationNs);
    this.samplesPerWindow = samplesPerWindow;

    carriedOverSamples = new long[CARRIED_OVER_ARRAY_SIZE];
  }

  /**
   * Create a new sampler instance
   *
   * @param windowDuration the sampling window duration
   * @param samplesPerWindow the maximum number of samples in the sampling window
   */
  StreamingSampler(final Duration windowDuration, final int samplesPerWindow) {
    this(windowDuration, samplesPerWindow, System::nanoTime);
  }

  /**
   * Provides binary answer whether the current event is to be sampled
   *
   * @return {@literal true} if the event should be sampled
   */
  boolean sample() {
    boolean sampled = false;
    testCounter.incrementAndGet();
    if (sampledCounter.get() <= samplesPerWindow + extraBudget.get()) {
      if (ThreadLocalRandom.current().nextDouble() < probability) {
        sampledCounter.incrementAndGet();
        sampled = true;
      }
    } else {
      overshootCounter.incrementAndGet();
    }

    final long ts = tsProvider.get();
    final long extraDuration = ts - nextWindowTs.get();
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
              + extraBudget
              + " extra duration: "
              + extraDuration
              + " probability: "
              + probability
              + " total count: "
              + totalCount
              + " overshoot count: "
              + overshootCounter.getAndSet(0));*/

          final long unused = samplesPerWindow - sampledCount;
          carriedOverSampleIndex = (carriedOverSampleIndex + 1) % carriedOverSamples.length;
          carriedOverSamples[carriedOverSampleIndex] = unused;
          long newExtraBudget = 0;
          for (int i = 0; i < carriedOverSamples.length; i++) {
            newExtraBudget += carriedOverSamples[i];
          }
          newExtraBudget = Math.max(newExtraBudget, 0);
          extraBudget.set(newExtraBudget);

          if (totalCountRunningAverage == 0) {
            totalCountRunningAverage = totalCount;
          } else {
            totalCountRunningAverage =
                (1 - EMA_ALPHA) * totalCountRunningAverage + EMA_ALPHA * totalCount;
          }

          if (totalCountRunningAverage <= 0) {
            probability = 1;
          } else {
            probability =
                Math.min(
                    ((double) samplesPerWindow
                            + (double) newExtraBudget / carriedOverSamples.length)
                        / totalCountRunningAverage,
                    1d);
          }

          nextWindowTs.addAndGet(windowDurationNs);
        } finally {
          endOfWindowLock.unlock();
        }
      }
    }

    return sampled;
  }
}
