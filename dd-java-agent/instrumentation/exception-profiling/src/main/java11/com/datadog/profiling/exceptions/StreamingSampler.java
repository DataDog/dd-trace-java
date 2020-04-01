package com.datadog.profiling.exceptions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
  private static final Logger LOGGER = LoggerFactory.getLogger(StreamingSampler.class);
  /*
   * Exponential Moving Average (EMA) last element weight.
   * Check out papers about using EMA for streaming data - eg.
   * https://nestedsoftware.com/2018/04/04/exponential-moving-average-on-streaming-data-4hhl.24876.html
   *
   * Alpha of 0.15 corresponds to the last ~20 significant values (the weight of the value is >= than the weight
   * if computed via arithmetic average).
   * Relative weight of a (N-k)-th element vs. the most recent one (N-0)-th element can be computed as (1 - alpha)^k.
   * In this case (1 - 0.15)^20 ~= 0.04 which is less than 1/20 which would be assigned by arithmetic average.
   */
  private static final double EMA_ALPHA = 0.15d;

  /*
   * We keep a 'budget' by counting number of unused samples from all the past windows (kind of) - using exponential
   * moving average to re-assign value weights to provide good average estimation.
   *
   * Alpha of 0.15 corresponds to the last ~20 significant values (the weight of the value is >= than the weight
   * if computed via arithmetic average).
   * Relative weight of a (N-k)-th element vs. the most recent one (N-0)-th element can be computed as (1 - alpha)^k.
   * In this case (1 - 0.15)^20 ~= 0.04 which is less than 1/20 which would be assigned by arithmetic average.
   */
  private static final double BUDGET_ALPHA = 0.15d;

  private final Supplier<Long> tsProvider;
  private final long windowDurationNs;
  private final int samplesPerWindow;

  private final LongAdder testCounter = new LongAdder();
  private final AtomicLong sampledCounter = new AtomicLong(0L);
  private final ReentrantLock endOfWindowLock = new ReentrantLock();
  private final AtomicLong extraBudget = new AtomicLong(0);

  // these attributes need to be volatile since they are accessed outside of the 'endOfWindowLock' guarded block
  private volatile double probability = 1d;
  private volatile long nextWindowTs = 0L;

  // these attributes are accessed solely under `endOfWindowLock`
  private double totalCountRunningAverage = 0d;
  private double avgBudget = Double.NaN;

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
  final boolean sample() {
    boolean sampled = false;
    testCounter.increment();
    if (sampledCounter.get() <= samplesPerWindow + extraBudget.get()) {
      if (ThreadLocalRandom.current().nextDouble() < probability) {
        sampledCounter.incrementAndGet();
        sampled = true;
      }
    }

    final long ts = tsProvider.get();
    final long extraDuration = ts - nextWindowTs;
    final boolean isExpired = extraDuration >= 0;

    if (isExpired) {
      if (endOfWindowLock.tryLock()) {
        try {
          final long totalCount = testCounter.sumThenReset();
          final long sampledCount = sampledCounter.getAndSet(0);

          long sampleBudget = samplesPerWindow - sampledCount;
          avgBudget = Double.isNaN(avgBudget) ? sampleBudget : avgBudget + BUDGET_ALPHA * (sampleBudget - avgBudget);
          extraBudget.set(Math.round(Math.max(avgBudget, 0) * 1));

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
                (samplesPerWindow + avgBudget)
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
}
