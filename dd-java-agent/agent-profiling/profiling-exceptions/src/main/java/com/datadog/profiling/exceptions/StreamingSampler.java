package com.datadog.profiling.exceptions;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * A streaming (non-remembering) sampler.
 *
 * <p>The sampler attempts to generate at most N samples per fixed time window in randomized
 * fashion. It is also using constant updates of the estimated event set per window such that
 * it can vary the expected sampling interval (how many events are between two samples in average)
 * to cover the events within one window by approximately the number of requested samples per window.
 * Due to all the numbers being just estimates the actual number of samples may vary slightly (the tests show
 * the variability being around 20%) and it must be understood that the expected number of samples per window
 * is not a hard/precise limit.
 */
public class StreamingSampler {
  /**
   * Immutable sampler state wrapper. Provides methods to derive a new state on arriving sample or
   * window roll.
   */
  static final class SamplerState {
    private final AtomicLong eventCounter;
    // was this state created for a sampling test?
    private final AtomicBoolean sampledFlag;
    // was this state create for a window roll?
    private final AtomicBoolean expiredFlag;
    final long samples;
    final double threshold;
    final long windowStartTs;
    final long windowEndTs;
    final long windowDurationNs;
    final long samplesPerWindow;

    private final Supplier<Long> tsProvider;

    private SamplerState(
      final long events,
      final double threshold,
      final long samples,
      final long samplesPerWindow,
      final long windowDurationNs,
      final long windowStartTs,
      final boolean sampled,
      final boolean expired,
      final Supplier<Long> tsProvider) {
      eventCounter = new AtomicLong(events);
      this.threshold = threshold;
      this.samples = samples;

      this.windowDurationNs = windowDurationNs;
      this.windowStartTs = windowStartTs;
      windowEndTs = windowStartTs + windowDurationNs;
      this.samplesPerWindow = samplesPerWindow;
      this.tsProvider = tsProvider;
      sampledFlag = new AtomicBoolean(sampled);
      expiredFlag = new AtomicBoolean(expired);
    }

    SamplerState(
      final long events,
      final long interval,
      final long samples,
      final long samplesPerWindow,
      final long windowDurationNs,
      final Supplier<Long> tsProvider) {
      this(
        events,
        interval,
        samples,
        samplesPerWindow,
        windowDurationNs,
        tsProvider.get(),
        false,
        false,
        tsProvider);
    }

    SamplerState trySample() {
      final long tested = eventCounter.incrementAndGet();
      // test a uniformly distributed random number against the current threshold
      final boolean isSampled = ThreadLocalRandom.current().nextDouble() <= threshold;

      final long ts = tsProvider.get();
      // a state is expired if the current time stamp is beyond the expected window end time stamp
      final boolean isExpired = ts > windowEndTs;
      if (isSampled || isExpired) {
        /*
         * Get the estimated event set size per the sampling window given the up-to-now incoming rate and the window duration.
         */
        final double estimatedSetSize = ((double) tested / (ts - windowStartTs)) * windowDurationNs;
        /*
         * Derive the desired sampling interval as such the expected number of samples can cover the estimated size
         * in one sampling window.
         */
        final long interval =
          Math.max(Math.round(estimatedSetSize / (samplesPerWindow - samples + 1)), 1);
        // generate a new derived immutable state
        return new SamplerState(
          isExpired ? 0 : tested,
          computeThreshold(samplesPerWindow, samples, interval),
          isExpired ? 0 : samples + 1,
          samplesPerWindow,
          windowDurationNs,
          isExpired ? ts : windowStartTs,
          isSampled,
          isExpired,
          tsProvider);
      }
      return this;
    }

    /**
     * Checks whether the state was created for a sampling test.<br>
     * After this method is invoked all subsequent invocations will return {@literal false}
     *
     * @return {@literal true} only if this is the first invocation and the state was created for a
     * sampling test
     */
    boolean sampled() {
      return sampledFlag.getAndSet(false);
    }

    /**
     * Checks whether the state was created for a window roll.<br>
     * After this method is invoked all subsequent invocations will return {@literal false}
     *
     * @return {@literal true} only if this is the first invocation and the state was created for a
     * window roll
     */
    boolean expired() {
      return expiredFlag.getAndSet(false);
    }

    /**
     * Use geometric cumulative distribution function (CDF) to calculate the threshold against which
     * to test a uniformly distributed random number to decide whether the current test should yield
     * sample or not.
     */
    private static double computeThreshold(
      final long samplesPerWindow, final long samples, final long interval) {
      /*
       * The probability 'p' is calculated as the ratio between the outstanding samples per current window and the total
       * expected sample per window.
       */
      final long samplesDiff = samplesPerWindow - samples;
      final double p = (double) samplesDiff / (samplesPerWindow + 1);

      /*
       * The CDF '1−(1−p)^x+1', where 'x' is the tested interval, will give the probability of a sample appearing in
       * the next 'x' tests.
       */
      return 1 - Math.pow(1 - p, interval + 1);
    }

    @Override
    public String toString() {
      return "SamplerState{"
        + "eventCount="
        + eventCounter.get()
        + ", samples="
        + samples
        + ", threshold="
        + threshold
        + ", windowStartTs="
        + windowStartTs
        + ", windowEndTs="
        + windowEndTs
        + ", windowDurationNs="
        + windowDurationNs
        + ", samplesPerWindow="
        + samplesPerWindow
        + ", sampledFlag="
        + sampledFlag
        + '}';
    }
  }

  private final AtomicReference<SamplerState> stateRef = new AtomicReference<>();

  /**
   * Create a new sampler instance
   *
   * @param samplingWindowDuration the sampling window duration
   * @param slidingWindowUnit      the time unit for the sampling window duration
   * @param maxSamplesInWindow     the maximum number of samples in the sampling window
   */
  public StreamingSampler(
    final long samplingWindowDuration,
    final TimeUnit slidingWindowUnit,
    final int maxSamplesInWindow) {
    this(samplingWindowDuration, slidingWindowUnit, maxSamplesInWindow, System::nanoTime);
  }

  StreamingSampler(
    final long windowDurationNs,
    final TimeUnit windowDurationUnit,
    final int samplesPerWindow,
    final Supplier<Long> tsProvider) {
    stateRef.set(
      new SamplerState(
        0,
        10,
        0L,
        samplesPerWindow,
        TimeUnit.NANOSECONDS.convert(windowDurationNs, windowDurationUnit),
        tsProvider));
  }

  /**
   * Provides binary answer whether the current event is to be sampled
   *
   * @return {@literal true} if the event should be sampled
   */
  public boolean sample() {
    // atomically test and update the state
    final SamplerState sampledState = stateRef.updateAndGet(SamplerState::trySample);

    // do not invoke the callback from the concurrent update part to minimize collision probability
    if (sampledState != null) {
      if (sampledState.expired()) {
        onWindowRoll(sampledState);
      }
      if (sampledState.sampled()) {
        onSample(sampledState);
        return true;
      }
    }
    return false;
  }

  /**
   * A custom callback to observe sample event. Mostly for debugging purposes.
   *
   * @param state the sampler state after taking this sample
   */
  protected void onSample(final SamplerState state) {
  }

  /**
   * A custom callback to observe rolling of the sampling window. Mostly for debugging purposes.
   *
   * @param state the sampler state after rolling the window
   */
  protected void onWindowRoll(final SamplerState state) {
  }
}
