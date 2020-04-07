package com.datadog.profiling.exceptions;

import datadog.common.exec.CommonTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

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
  private static final Logger LOGGER = LoggerFactory.getLogger(StreamingSampler.class);

  private static final class Counts {
    private final LongAdder testCounter = new LongAdder();
    private final AtomicLong sampleCounter = new AtomicLong(0L);

    void addTest() {
      testCounter.increment();
    }

    long addSample(long limit) {
      return sampleCounter.getAndUpdate(s -> s + (s < limit ? 1 : 0));
    }
  }

  /*
   * Exponential Moving Average (EMA) last element weight.
   * Check out papers about using EMA for streaming data - eg.
   * https://nestedsoftware.com/2018/04/04/exponential-moving-average-on-streaming-data-4hhl.24876.html
   *
   * Corresponds to 'lookback' of N values:
   * With T being the index of the most recent value the lookback of N values means that for all values with index
   * T-K, where K > N, the relative weight of that value computed as (1 - alpha)^K is less or equal than the
   * weight assigned by a plain arithmetic average (= 1/N).
   */
  private final double emaAlpha;
  private final int samplesPerWindow;

  private final AtomicReference<Counts> countsRef;
  private final int lookback;

  // these attributes need to be volatile since they are accessed outside of the 'endOfWindowLock' guarded block
  private volatile double probability = 1d;
  private volatile long targetSamples = 0L;

  // these attributes are accessed solely from the window maintenance thread
  private double totalCountRunningAverage = 0d;
  private double avgBudget = Double.NaN;
  private long windowCount = 0L;

  /**
   * Create a new sampler instance
   *
   * @param windowDuration      the sampling window duration
   * @param samplesPerWindow    the maximum number of samples in the sampling window
   * @param lookback            the number of windows to consider in averaging the sampling rate
   * @param startWindowRolling  should the scheduled window roll to be started; useful for testing with manual rolls
   */
  StreamingSampler(
    final Duration windowDuration, final int samplesPerWindow, final int lookback, boolean startWindowRolling) {
    this.samplesPerWindow = samplesPerWindow;
    this.targetSamples = Math.round(samplesPerWindow * 0.92d);
    this.lookback = lookback;
    this.emaAlpha = computeLookbackAlpha(lookback);
    this.countsRef = new AtomicReference<>(new Counts());

    if (startWindowRolling) {
      CommonTaskExecutor.INSTANCE.scheduleAtFixedRate(this::rollWindow, windowDuration.getNano(), windowDuration.getNano(), TimeUnit.NANOSECONDS);
    }
  }

  /**
   * Create a new sampler instance with automatic window roll.
   *
   * @param windowDuration   the sampling window duration
   * @param samplesPerWindow the maximum number of samples in the sampling window
   * @param lookback         the number of windows to consider in averaging the sampling rate
   */
  StreamingSampler(final Duration windowDuration, final int samplesPerWindow, final int lookback) {
    this(windowDuration, samplesPerWindow, lookback, false);
  }

  /**
   * Provides binary answer whether the current event is to be sampled
   *
   * @return {@literal true} if the event should be sampled
   */
  final boolean sample() {
    Counts counts = countsRef.get();
    counts.addTest();
    if (ThreadLocalRandom.current().nextDouble() < probability) {
      return counts.addSample(targetSamples) < targetSamples;
    }

    return false;
  }

  // package private access for the tests
  final void rollWindow() {
    windowCount = Math.min(windowCount + 1, lookback);

    /*
     * Atomically replace the Counts instance such that sample requests during window maintenance will be
     * using the newly created counts instead of the ones currently processed by the maintenance routine.
     */

    Counts counts = countsRef.getAndSet(new Counts());
    final long totalCount = counts.testCounter.sum();
    final long sampledCount = counts.sampleCounter.get();

    long sampleBudget = samplesPerWindow - sampledCount;
    avgBudget = Double.isNaN(avgBudget) ? sampleBudget : avgBudget + emaAlpha * (sampleBudget - avgBudget);
    targetSamples = samplesPerWindow + Math.round(Math.max(avgBudget, 0) * windowCount);

    if (totalCountRunningAverage == 0) {
      totalCountRunningAverage = totalCount;
    } else {
      totalCountRunningAverage = totalCountRunningAverage + emaAlpha * (totalCount - totalCountRunningAverage);
    }

    if (totalCountRunningAverage <= 0) {
      probability = 1;
    } else {
      probability =
        Math.min(
          (samplesPerWindow + avgBudget)
            / totalCountRunningAverage,
          1d);
    }
  }

  private static double computeLookbackAlpha(int lookback) {
    return 1 - Math.pow(lookback, -1d / lookback);
  }
}
