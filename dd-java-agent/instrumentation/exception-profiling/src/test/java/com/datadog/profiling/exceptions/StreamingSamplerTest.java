package com.datadog.profiling.exceptions;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableList;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opentest4j.AssertionFailedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class StreamingSamplerTest {

  private static final Logger LOGGER = LoggerFactory.getLogger(StreamingSamplerTest.class);

  private static final Duration WINDOW_DURATION = Duration.ofSeconds(1);
  private static final double DURATION_ERROR_MARGIN = 20;
  private static final double SAMPLES_ERROR_MARGIN = 10;
  private static final double CUTOFF_ERROR_MARGIN = 5;

  private interface TimestampProvider extends Supplier<Long> {
    default void prepare() {}

    default void cleanup() {}

    long getLast();
  }

  private abstract static class MonotonicTimestampProvider implements TimestampProvider {
    private final AtomicLong timestamp = new AtomicLong(0L);
    protected final long interval;

    protected MonotonicTimestampProvider(final Duration totalDuration, final int numberOfEvents) {
      interval = Math.round(totalDuration.toNanos() / (double) numberOfEvents);
    }

    @Override
    public Long get() {
      return timestamp.getAndAdd(computeRandomStep(interval));
    }

    @Override
    public long getLast() {
      return timestamp.get();
    }

    @Override
    public void prepare() {
      timestamp.set(0L);
    }

    protected abstract long computeRandomStep(long step);
  }

  private static final class ExponentialTimestampProvider extends MonotonicTimestampProvider {
    public ExponentialTimestampProvider(Duration totalDuration, int numberOfEvents) {
      super(totalDuration, numberOfEvents);
    }

    @Override
    protected long computeRandomStep(long step) {
      return Math.round(-step * Math.log(1 - ThreadLocalRandom.current().nextDouble()));
    }

    @Override
    public String toString() {
      return String.format("Exponential: %d", interval);
    }
  }

  private static final class GaussianTimestampProvider extends MonotonicTimestampProvider {
    GaussianTimestampProvider(final Duration totalDuration, final int numberOfEvents) {
      super(totalDuration, numberOfEvents);
    }

    @Override
    protected long computeRandomStep(long step) {
      return Math.round(
          Math.max(step + ((ThreadLocalRandom.current().nextGaussian() * step) * 1.0d), 0));
    }

    @Override
    public String toString() {
      return String.format("Gaussian: %d", interval);
    }
  }

  private static final class UniformTimestampProvider implements TimestampProvider {
    private static final int MARGIN = 2; // generate some extra events;
    private final AtomicInteger counter = new AtomicInteger(0);
    private final Duration totalDuration;
    private final int totalEvents;
    private long[] events;

    UniformTimestampProvider(final Duration totalDuration, final int totalEvents) {
      this.totalDuration = totalDuration;
      this.totalEvents = totalEvents;
    }

    @Override
    public Long get() {
      return events[counter.getAndIncrement()];
    }

    @Override
    public void prepare() {
      final Random random = new Random();
      events =
          random
              .longs(totalEvents * MARGIN, 0, totalDuration.toNanos() * MARGIN)
              .sorted()
              .toArray();
      counter.set(0);
    }

    @Override
    public void cleanup() {
      events = null;
    }

    @Override
    public long getLast() {
      return events[counter.get()];
    }

    @Override
    public String toString() {
      return "Uniform";
    }
  }

  @ParameterizedTest(
      name =
          "{index} threadCount={0} timestamp={1} requestedEvents={2} requestedDuration={3} windowDuration={4} samplesPerWindow={5}")
  @MethodSource("samplerParams")
  void sample(
      final int threadCount,
      final TimestampProvider timestampProvider,
      final int requestedEvents,
      final Duration requestedDuration,
      final Duration windowDuration,
      final int samplesPerWindow)
      throws Exception {
    /*
     * This test is probabilistic and an unlucky series of random events can break the error margins.
     * Retry a failed test several times to identify systemic failures.
     */
    long retries = 1;
    while (true) {
      try {
        runSampleTest(
            threadCount,
            timestampProvider,
            requestedEvents,
            requestedDuration,
            windowDuration,
            samplesPerWindow);
        break;
      } catch (AssertionFailedError failed) {
        LOGGER.warn("{}. attempt failed ({}). Retrying.", retries, failed.getLocalizedMessage());
        if (++retries == 4) {
          throw failed;
        }
      }
    }
  }

  private void runSampleTest(
      int threadCount,
      TimestampProvider timestampProvider,
      int requestedEvents,
      Duration requestedDuration,
      Duration windowDuration,
      int samplesPerWindow)
      throws InterruptedException {
    try {
      LongAdder allCutOff = new LongAdder();
      LongAdder allCount = new LongAdder();
      LongAdder allSamples = new LongAdder();
      timestampProvider.prepare();
      final StreamingSampler sampler =
          new StreamingSampler(windowDuration, samplesPerWindow, timestampProvider) {
            private final AtomicBoolean firstWindow = new AtomicBoolean(true);

            @Override
            void onWindow(
                long eventCount,
                long sampledCount,
                long cutOffCount,
                double avgBudget,
                double probability,
                long nextWindowTs) {
              // discard the first window since the cutoff is extremely high due to no history
              if (!firstWindow.getAndSet(false)) {
                if (cutOffCount > 0) {
                  LOGGER.debug(
                      "{} ({}%) events were cut-off from sampling in window while yielding {} samples",
                      cutOffCount, ((double) cutOffCount / eventCount) * 100, sampledCount);
                }
                allCount.add(eventCount);
                allCutOff.add(cutOffCount);
                allSamples.add(sampledCount);
              }
            }
          };

      final long actualSamples = runThreadsAndCountSamples(sampler, threadCount, requestedEvents);

      // We assume that our fake timestamp providers start at zero
      final Duration actualDuration = Duration.ofNanos(timestampProvider.getLast());

      final double durationDiscrepancy =
          (double) Math.abs(requestedDuration.toMillis() - actualDuration.toMillis())
              / requestedDuration.toMillis()
              * 100;

      final double expectedSamples =
          ((double) actualDuration.toNanos() / windowDuration.toNanos() * samplesPerWindow);
      final long samplesDiscrepancy =
          Math.round(Math.abs(expectedSamples - actualSamples) / expectedSamples * 100);

      final String message =
          String.format(
              "Expected to get within %.1f%% of requested samples: abs(%.1f - %d) / %.1f = %d%%",
              SAMPLES_ERROR_MARGIN,
              expectedSamples,
              actualSamples,
              expectedSamples,
              samplesDiscrepancy);
      LOGGER.debug(message);
      assertTrue(samplesDiscrepancy <= SAMPLES_ERROR_MARGIN, message);

      assertTrue(
          durationDiscrepancy <= DURATION_ERROR_MARGIN,
          String.format(
              "Expected to run within %.1f%% of requested duration: abs(%d - %d) / %d = %.1f%%",
              DURATION_ERROR_MARGIN,
              requestedDuration.toMillis(),
              actualDuration.toMillis(),
              requestedDuration.toMillis(),
              durationDiscrepancy));

      double samplingRate = allCount.doubleValue() / allSamples.longValue();
      long missedSamples = Math.round(allCutOff.longValue() / samplingRate);
      final double cutOffError = (missedSamples / allSamples.doubleValue()) * 100;
      assertTrue(
          cutOffError <= CUTOFF_ERROR_MARGIN,
          String.format(
              "Expected to cut off not more than %.1f%% of total events: (%d / %d) * 100 = %.2f%%",
              CUTOFF_ERROR_MARGIN, missedSamples, allSamples.longValue(), cutOffError));
    } finally {
      timestampProvider.cleanup();
    }
  }

  private int runThreadsAndCountSamples(
      final StreamingSampler sampler, final int threadCount, final int totalEvents)
      throws InterruptedException {
    final long eventsPerThread = totalEvents / threadCount;

    final AtomicInteger totalSamplesCounter = new AtomicInteger(0);
    final Thread[] threads = new Thread[threadCount];
    for (int j = 0; j < threads.length; j++) {
      threads[j] =
          new Thread(
              () -> {
                int samplesCount = 0;
                for (long i = 0; i <= eventsPerThread; i++) {
                  if (sampler.sample()) {
                    samplesCount += 1;
                  }
                }
                totalSamplesCounter.addAndGet(samplesCount);
              });
      threads[j].start();
    }
    for (final Thread thread : threads) {
      thread.join();
    }
    //    LOGGER.debug("Overshoot ratio: {}", sampler.overshootRatio());

    return totalSamplesCounter.get();
  }

  private static Stream<Arguments> samplerParams() {
    final List<Integer> totalEvents = ImmutableList.of(25_000, 25_000, 300_000, 1_000_000);
    final List<Integer> runDurations = ImmutableList.of(30, 60, 120, 600);
    final List<Arguments> args = new ArrayList<>();
    for (int threadCount = 1; threadCount <= 64; threadCount *= 2) {
      for (int samples = 16; samples <= 256; samples *= 2) {
        for (int i = 0; i < totalEvents.size(); i++) {
          final Duration duration = Duration.ofSeconds(runDurations.get(i));

          final List<TimestampProvider> timestampProviders =
              ImmutableList.of(
                  new ExponentialTimestampProvider(duration, totalEvents.get(i)),
                  new GaussianTimestampProvider(duration, totalEvents.get(i)),
                  new UniformTimestampProvider(duration, totalEvents.get(i)));

          for (final TimestampProvider timestampProvider : timestampProviders) {
            args.add(
                Arguments.of(
                    threadCount,
                    timestampProvider,
                    totalEvents.get(i),
                    duration,
                    WINDOW_DURATION,
                    samples));
          }
        }
      }
    }
    return args.stream();
  }
}
