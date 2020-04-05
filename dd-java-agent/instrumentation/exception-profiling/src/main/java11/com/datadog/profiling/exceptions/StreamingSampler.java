package com.datadog.profiling.exceptions;

import static java.lang.Double.isNaN;
import static java.lang.Math.max;
import static java.lang.Math.min;

import java.time.Duration;
import java.util.Arrays;
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
 * To smooth out these hicups the sampler maintains an over-sampling budget which can be used for compensate
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
  static final int CARRIED_OVER_ARRAY_SIZE = 16;

  /*
   * If we have not seen exceptions more than this number of windows we just zero out average number of events.
   * This value should not be too big since calculating EMA is linear.
   */
  static final int MAX_NUMBER_OF_SKIPPED_WINDOWS_TO_CALCULATE_EMA = 10;

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

  private final LongAdder eventCounter = new LongAdder();
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
    eventsPerWindowEmaAlpha = computeLookbackAlpha(lookback);

    carriedOverSamples = new long[Integer.max(min(CARRIED_OVER_ARRAY_SIZE, lookback), 1)];

    // Assume 'before' there were no event so we have 'full' budget.
    Arrays.fill(carriedOverSamples, samplesPerWindow);
    samplesBudget = samplesPerWindow + carriedOverSamples.length * samplesPerWindow;
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
            // Calculation below will take time, but it is possible that we got here having large budget and many parallel threads
            // throwing exceptions. So first step here is limit number of exceptions that we may collect until calculations are complete.
            samplesBudget = samplesPerWindow;

            long eventCount = eventCounter.sumThenReset();
            long sampledCount = sampledCounter.getAndSet(0);

            // FIXME: doing whole window closing inline results in more complex code and poor performance under test conditions
            // We probably should switch to separate thread.
            
            // Integer division rounds down, so this is number of windows that have passed
            final long passedWindows = (now - nextWindowTimestamp) / windowDuration;

            if (passedWindows > 0) {
              // We have missed windows - this means that only current event should be included
              eventCount = 1;
              sampledCount = 1;
            }

            // Budget includes events in next window.
            // FIXME: Adding extra samples budget to calculate probability is questionable because
            // we essentially make probability swing widely instead of being a stable value based upon long running average.
            // But empirically this seems to produce better results.
            final long newSamplesBudget = calculateNewSamplesBudget(sampledCount, passedWindows) + samplesPerWindow;

            if (passedWindows < MAX_NUMBER_OF_SKIPPED_WINDOWS_TO_CALCULATE_EMA) {
              // FIXME: is there an analytical formula for this?
              for (int i = 0; i < passedWindows; i++) {
                totalCountRunningAverage += eventsPerWindowEmaAlpha * (0 - totalCountRunningAverage);
              }
            } else {
              // Too many passed windows: forget everything!
              totalCountRunningAverage = 0;
            }

            if (isNaN(totalCountRunningAverage)) {
              totalCountRunningAverage = eventCount;
            } else {
              totalCountRunningAverage += eventsPerWindowEmaAlpha * (eventCount - totalCountRunningAverage);
            }

            if (totalCountRunningAverage <= 0) {
              probability = 1;
            } else {
              probability = min(newSamplesBudget / totalCountRunningAverage, 1d);
            }

            samplesBudget = newSamplesBudget;
            nextWindowTimestamp = getNextWindowTimestamp(nextWindowTimestamp + passedWindows * windowDuration);
          }
        } finally {
          endOfWindowLock.unlock();
        }
      }
    }

    // Sample after window has been updated: this makes us relatively sure that window is not over yet
    boolean sampled = false;
    eventCounter.increment();
    if (sampledCounter.get() < samplesBudget) {
      if (ThreadLocalRandom.current().nextDouble() < probability) {
        sampledCounter.incrementAndGet();
        sampled = true;
      }
    }

    return sampled;
  }

  /**
   * Calculate new samples budget based on carried over samples.
   *
   * <p>Note: this is expected to run under lock and is somewhat on critical path so we should make this as efficient as possible</p>
   */
  private long calculateNewSamplesBudget(final long sampledCount, final long passedWindows) {
    if (passedWindows > 0) {
      // Add unused samples from unseen windows
      final long passedWindowsForCarriedOverSamples = min(passedWindows, carriedOverSamples.length);
      for (int i = 0; i < passedWindowsForCarriedOverSamples; i++) {
        carriedOverSampleIndex = (carriedOverSampleIndex + 1) % carriedOverSamples.length;
        carriedOverSamples[carriedOverSampleIndex] = samplesPerWindow;
      }
    }

    // Add unused samples to budget and move budget index
    final long unusedSamplesCount = max(samplesPerWindow - sampledCount, 0);
    carriedOverSampleIndex = (carriedOverSampleIndex + 1) % carriedOverSamples.length;
    carriedOverSamples[carriedOverSampleIndex] = unusedSamplesCount;

    // Deduct 'overused' samples from the budget
    if (sampledCount > samplesPerWindow) {
      long overBudget = sampledCount - samplesPerWindow;
      // We have ring buffer, so 'next index' is actually last one
      int lastWindow = (carriedOverSampleIndex + 1) % carriedOverSamples.length;
      for (int i = 0; i < carriedOverSamples.length; i++) {
        if (overBudget > carriedOverSamples[lastWindow]) {
          overBudget -= carriedOverSamples[lastWindow];
          carriedOverSamples[lastWindow] = 0;
        } else {
          // This also handles the case when carriedOverSamples[lastWindow] == overBudget
          // - i.e. we no longer have overBudget and can exit
          carriedOverSamples[lastWindow] -= overBudget;
          break;
        }
        lastWindow = (lastWindow + 1) % carriedOverSamples.length;
      }
    }

    long newSamplesBudget = 0;
    for (final long s : carriedOverSamples) {
      newSamplesBudget += s;
    }
    return newSamplesBudget;
  }

  private long getNextWindowTimestamp(final long currentWindowTimestamp) {
    return currentWindowTimestamp + windowDuration;
  }

  private static double computeLookbackAlpha(final int lookback) {
    return 1 - Math.pow(lookback, -1d / lookback);
  }
}
