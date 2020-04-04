package com.datadog.profiling.exceptions;

import static java.lang.Double.isNaN;
import static java.lang.Integer.max;
import static java.lang.Math.min;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
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
   * We keep a 'budget' by counting number of unused samples form few last windows.
   * Exact value here should be on order of (but probably less than) size of the JFR chunk duration.
   * Another consideration to choosing this value is that we have to do linear number of operations
   * on resulting array every window so this value should not be too large.
   *
   * Note: we want sum of unused samples in previous windows - this is why we are using array for this and not EMA.
   * With EMA we would need to come up with some multiplier for and average and it's unclear how to do that.
   */
  private static final int CARRIED_OVER_ARRAY_SIZE = 16;

  /*
   * Exponential Moving Average (EMA) last element weight.
   * Check out papers about using EMA for streaming data - eg.
   * https://nestedsoftware.com/2018/04/04/exponential-moving-average-on-streaming-data-4hhl.24876.html
   *
   * Corresponds to 'lookback' of N windows:
   * With T being the index of the most recent value the lookback of N windows means that for all values with index
   * T-K, where K > N, the relative weight of that value computed as (1 - alpha)^K is less or equal than the
   * weight assigned by a plain arithmetic average (= 1/N).
   */
  private final double eventsPerWindowEmaAlpha;
  private final Supplier<Long> timestampProvider;
  private final long windowDuration;
  private final int samplesPerWindow;

  private final LongAdder testCounter = new LongAdder();
  private final AtomicLong sampledCounter = new AtomicLong(0L);
  private final ReentrantLock endOfWindowLock = new ReentrantLock();

  // these attributes need to be volatile since they are accessed outside of the 'endOfWindowLock' guarded block
  private volatile double probability = 1d;
  private volatile long samplesBudget = 0;
  private volatile long nextWindowTimestamp = 0L;

  // these attributes are accessed solely under `endOfWindowLock`
  private double totalCountRunningAverage = Double.NaN;
  private final long[] carriedOverSamples;
  private int carriedOverSampleIndex = 0;

  /**
   * Create a new sampler instance
   *
   * @param windowDuration   the sampling window duration
   * @param samplesPerWindow the maximum number of samples in the sampling window
   * @param lookback         the number of windows to consider in averaging the sampling rate
   */
  StreamingSampler(final Duration windowDuration, final int samplesPerWindow, final int lookback) {
    this(windowDuration, samplesPerWindow, lookback, System::nanoTime);
  }

  /**
   * Create a new sampler instance
   *
   * @param windowDuration    the sampling window duration
   * @param samplesPerWindow  the maximum number of samples in the sampling window
   * @param lookback          the number of windows making significant contribution in averaging the sampling rate
   * @param timestampProvider timestamp provider
   */
  StreamingSampler(
    final Duration windowDuration, final int samplesPerWindow, final int lookback, final Supplier<Long> timestampProvider) {
    this.timestampProvider = timestampProvider;
    this.windowDuration = windowDuration.toNanos();
    nextWindowTimestamp = getNextWindowTimestamp(timestampProvider.get());
    this.samplesPerWindow = samplesPerWindow;
    samplesBudget = samplesPerWindow;
    eventsPerWindowEmaAlpha = computeLookbackAlpha(lookback);

    carriedOverSamples = new long[max(min(CARRIED_OVER_ARRAY_SIZE, lookback), 1)];
  }

  /**
   * Provides binary answer whether the current event is to be sampled
   *
   * @return {@literal true} if the event should be sampled
   */
  final boolean sample() {
    final long now = timestampProvider.get();
    if (now >= nextWindowTimestamp) {
      if (endOfWindowLock.tryLock()) {
        try {
          if (now >= nextWindowTimestamp) {
            final long totalCount = testCounter.sumThenReset();
            final long sampledCount = sampledCounter.getAndSet(0);

            // Integer division rounds down, so this is number of windows that have passed
            final long passedWindows = (now - nextWindowTimestamp) / windowDuration;

            final long unusedSamplesCount = samplesPerWindow - sampledCount;
            carriedOverSampleIndex = (carriedOverSampleIndex + 1) % carriedOverSamples.length;
            carriedOverSamples[carriedOverSampleIndex] = unusedSamplesCount;

            // Budget includes events in next window
            // FIXME: Adding extra samples budget to calculate probability is questionable because
            // we essentially make probability swing widely instead of being a stable value based upon long running average.
            // But empirically this seems to produce better results.
            long newSamplesBudget = samplesPerWindow;
            for (int i = 0; i < carriedOverSamples.length; i++) {
              newSamplesBudget += carriedOverSamples[i];
            }
            newSamplesBudget = Math.max(newSamplesBudget, 0);
            samplesBudget = newSamplesBudget;

            if (isNaN(totalCountRunningAverage)) {
              totalCountRunningAverage = totalCount;
            } else {
              totalCountRunningAverage += eventsPerWindowEmaAlpha * (totalCount - totalCountRunningAverage);
            }

            if (totalCountRunningAverage <= 0) {
              probability = 1;
            } else {
              probability = min(newSamplesBudget / totalCountRunningAverage, 1d);
            }

            // FIXME: handle skipped windows
            nextWindowTimestamp = getNextWindowTimestamp(nextWindowTimestamp);
          }
        } finally {
          endOfWindowLock.unlock();
        }
      }
    }

    // Sample after window has been updated: this makes us relatively sure that window is not over yet
    boolean sampled = false;
    testCounter.increment();
    if (sampledCounter.get() <= samplesBudget) {
      if (ThreadLocalRandom.current().nextDouble() < probability) {
        sampledCounter.incrementAndGet();
        sampled = true;
      }
    }

    return sampled;
  }

  private long getNextWindowTimestamp(final long currentWindowTimestamp) {
    return currentWindowTimestamp + windowDuration;
  }

  private static double computeLookbackAlpha(final int lookback) {
    return 1 - Math.pow(lookback, -1d / lookback);
  }
}
