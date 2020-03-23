package com.datadog.profiling.exceptions;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * A streaming (non-remembering) sampler.
 *
 * <p>The sampler attempts to generate at most N samples per fixed time window in randomized
 * fashion. Because of very non-trivial nature of this task given the hard limit on samples and the
 * sampler being non-remembering the actual implementation is just an approximation. It is using
 * rather short 'sampling windows' (few seconds at max) during which at most N samples will be
 * taken. This allows for predictability of the number of samples after a certain period of time T
 * has elapsed ~ (T / W) * N, where W is the sampling window duration.
 *
 * <p>The sampler is using exponential CDF as a base for random probing, adjusted in a way that the
 * probability of the <b>hit</b> goes to 0 rapidly once the sample limit was hit. The 'lambda'
 * parameter of the exponential distribution (or rate) is adjusted from window to window to better
 * reflect the expected rate considering the number of events per the sampling window and the sample
 * limit. Using this trick it is possible to slightly compensate for the samples being skewed,
 * favoring the earlier events.
 */
public class StreamingSampler {
  /** Using scale to avoid floating point comparisons */
  private static final long SCALE = 10_000_000L;

  private final long samplingWindowDuration;
  private final int samplingWindowSize;

  private final Supplier<Long> timeStampSupplier;
  private final LongAdder hitCounter = new LongAdder();

  private final AtomicLong samplingWindowEndTsRef;
  private final AtomicLong thresholdRef;

  private long lambda;
  private volatile long sampleCounter = 0L;

  /**
   * Create a new sampler instance
   *
   * @param samplingWindowDuration the sampling window duration
   * @param slidingWindowUnit the time unit for the sampling window duration
   * @param maxSamplesInWindow the maximum number of samples in the sampling window
   */
  public StreamingSampler(
      final long samplingWindowDuration,
      final TimeUnit slidingWindowUnit,
      final int maxSamplesInWindow) {
    this(samplingWindowDuration, slidingWindowUnit, maxSamplesInWindow, System::nanoTime);
  }

  StreamingSampler(
      final long samplingWindowDuration,
      final TimeUnit slidingWindowUnit,
      final int samplingWindowSize,
      final Supplier<Long> timeStampSupplier) {
    this.samplingWindowDuration =
        TimeUnit.NANOSECONDS.convert(samplingWindowDuration, slidingWindowUnit);
    this.samplingWindowSize = samplingWindowSize;
    samplingWindowEndTsRef = new AtomicLong(timeStampSupplier.get() + this.samplingWindowDuration);
    lambda = 10;
    thresholdRef = new AtomicLong(computeThreshold(0, lambda));
    this.timeStampSupplier = timeStampSupplier;
  }

  /**
   * Provides binary answer whether the current event is to be sampled
   *
   * @return {@literal true} if the event should be sampled
   */
  public boolean sample() {
    boolean result = false;
    hitCounter.increment();
    final long threshold = thresholdRef.get();
    final long s = sampleCounter;
    if (test(threshold)) {
      long newSampleCount = -1;
      long newThreshold = -1;
      /*
       * Trying to do this completely lock-free leads to a very complex code which tends to blow up the hard limits.
       * Let's just do the sane thing and put the critical block, which is kept very minimal, into synchronized section.
       * For our purposes the crucial part is making sure we haven't already got sample hit for the same sample number.
       */
      synchronized (this) {
        if (sampleCounter == s) {
          newSampleCount = s + 1;
          newThreshold = computeThreshold(newSampleCount, lambda);
          thresholdRef.set(newThreshold);
          sampleCounter = newSampleCount;
          result = true;
        }
      }
      // do not invoke the callback from the synchronized part to minimize time spent in locked
      // section
      if (newSampleCount != -1) {
        onSample(newSampleCount, threshold, newThreshold, lambda);
      }
    }
    tryCloseWindow(threshold);
    return result;
  }

  private void tryCloseWindow(final long threshold) {
    final long ts = timeStampSupplier.get();
    final long tsEnd = samplingWindowEndTsRef.get();
    if (ts >= tsEnd) {
      thresholdRef.getAndUpdate(
          v -> {
            if (v == threshold) {
              final long hits = hitCounter.sumThenReset();
              if (samplingWindowEndTsRef.compareAndSet(tsEnd, ts + samplingWindowDuration)) {
                final long origLambda = lambda;
                final long newLambda = (hits - samplingWindowSize) / samplingWindowSize;
                synchronized (this) {
                  sampleCounter = 0;
                  lambda = newLambda;
                }
                final long newThreshold = computeThreshold(0, newLambda);
                onWindowEnd(threshold, newThreshold, origLambda, lambda);
                return newThreshold;
              } else {
                return v;
              }
            }
            return v;
          });
    }
  }

  private long computeThreshold(final long samples, final long lambda) {
    // use quasi-exponential CDF to quickly converge to 0 once crossing the samplingWindowSize
    final double e = Math.max(1 - Math.exp((-1d * (samplingWindowSize - samples)) / lambda), 0);
    return Math.round(SCALE * e);
  }

  private boolean test(final long value) {
    return ThreadLocalRandom.current().nextLong(SCALE) <= value;
  }

  /**
   * A custom callback to observe closing of the sampling window. Mostly for debugging purposes.
   *
   * @param origThreshold threshold of the previous window
   * @param newThreshold threshold of the new window
   * @param origLambda lambda of the previous window
   * @param newLambda lamda of the new window
   */
  protected void onWindowEnd(
      final long origThreshold,
      final long newThreshold,
      final long origLambda,
      final long newLambda) {}

  /**
   * A custom callback to observe sample event. Mostly for debugging purposes.
   *
   * @param samples the number of samples processed so far
   * @param origThreshold threshold used to get this sample
   * @param newThreshold threshold for the next sample
   * @param lambda the lambda used to get the sample
   */
  protected void onSample(
      final long samples, final long origThreshold, final long newThreshold, final long lambda) {}
}
