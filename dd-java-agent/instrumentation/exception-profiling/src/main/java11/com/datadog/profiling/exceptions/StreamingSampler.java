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
 * fashion. For this it divides the timeline into 'profiling windows' of constant length/duration.
 * Each profiling window targets a constant number of samples which are scattered randomly (uniform distribution)
 * throughout the window duration and once the window is over the real stats of incoming events and the number of
 * gathered samples is used to recompute the target probability to use in the following window.
 * </p>
 * <p>
 * This will guarantee, if the windows are not excessively large, that the sampler will be able to adjust
 * to the changes in the rate of incoming events.
 * </p>
 * <p>
 * However, there might so rapid changes in incoming events rate that we will optimistically use all allowed samples
 * well before the current window has elapsed or, on the other end of the spectrum, there will be to few incoming events
 * and the sampler will not be able to generate the target number of samples.
 * </p>
 * <p>
 * To smooth out these hicups the sampler maintains an under/over-sampling budget which can be used for compensate
 * for too rapid changes in the incoming events rate and maintain the target average number of samples per window.
 * </p>
 */
class StreamingSampler {
  /*
   * Exponential Moving Average (EMA) last element weight.
   * Check out papers about using EMA for streaming data - eg.
   * https://nestedsoftware.com/2018/04/04/exponential-moving-average-on-streaming-data-4hhl.24876.html
   */
  private static final double EMA_ALPHA = 0.01d;

  /*
   * We keep a 'budget' by counting number of unused samples from the last 10 windows
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
  private final AtomicLong extraBudget = new AtomicLong(0);
  private final long[] carriedOverSamples = new long[CARRIED_OVER_ARRAY_SIZE];

  // these attributes need to be volatile since they are accessed outside of the 'endOfWindowLock' guarded block
  private volatile double probability = 1d;
  private volatile long nextWindowTs = 0L;

  private double totalCountRunningAverage = 0d;

  private int carriedOverSampleIndex = 0;

  /**
   * Create a new sampler instance
   *
   * @param windowDuration   the sampling window duration
   * @param samplesPerWindow the maximum number of samples in the sampling window
   * @param tsProvider       timestamp provider
   */
  StreamingSampler(
    final Duration windowDuration, final int samplesPerWindow, final Supplier<Long> tsProvider) {
    this.tsProvider = tsProvider;
    windowDurationNs = windowDuration.toNanos();
    nextWindowTs = tsProvider.get() + windowDurationNs;
    this.samplesPerWindow = samplesPerWindow;
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
    final long extraDuration = ts - nextWindowTs;
    final boolean isExpired = extraDuration >= 0;

    if (isExpired) {
      if (endOfWindowLock.tryLock()) {
        try {
          final long totalCount = testCounter.getAndSet(0);
          final long sampledCount = sampledCounter.getAndSet(0);

          long newExtraBudget = recalculateBudget(samplesPerWindow - sampledCount);

          extraBudget.set(newExtraBudget);

          if (totalCountRunningAverage == 0) {
            totalCountRunningAverage = totalCount;
          } else {
            totalCountRunningAverage = totalCountRunningAverage + EMA_ALPHA * (totalCount - totalCountRunningAverage);
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

          nextWindowTs += windowDurationNs;
        } finally {
          endOfWindowLock.unlock();
        }
      }
    }

    return sampled;
  }

  /**
   * Recalculates the time budget with the latest 'unusedSamples' value.
   * <b>Must be called under 'endOfWindowLock' lock to ensure proper concurrency handling</b>
   *
   * @param unusedSamples the number of currently unused samples
   * @return new value of sampling budget based on the values captured in last CARRIED_OVER_ARRAY_SIZE windows
   */
  private long recalculateBudget(long unusedSamples) {
    carriedOverSampleIndex = (carriedOverSampleIndex + 1) % carriedOverSamples.length;
    carriedOverSamples[carriedOverSampleIndex] = unusedSamples;
    long newExtraBudget = 0;
    for (long carriedOverSample : carriedOverSamples) {
      newExtraBudget += carriedOverSample;
    }
    newExtraBudget = Math.max(newExtraBudget, 0);
    return newExtraBudget;
  }
}
