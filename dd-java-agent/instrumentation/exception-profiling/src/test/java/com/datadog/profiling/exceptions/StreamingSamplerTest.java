package com.datadog.profiling.exceptions;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableList;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class StreamingSamplerTest {

  private static final Logger LOGGER = LoggerFactory.getLogger(StreamingSamplerTest.class);

  private static final Duration WINDOW_DURATION = Duration.ofSeconds(1);
  private static final int SAMPLER_LOOKBACK = 60;
  private static final double DURATION_ERROR_MARGIN = 10;
  private static final double SAMPLES_ERROR_MARGIN = 10;

  private interface TimestampProvider extends Supplier<Long> {

    void prepare();

    void cleanup();

    long getFirst();

    long getLast();
  }

  private abstract static class MonotonicTimestampProvider implements TimestampProvider {

    private final AtomicLong timestamp = new AtomicLong(0L);
    protected final long interval;

    protected MonotonicTimestampProvider(final Duration totalDuration, final int numberOfEvents) {
      interval = Math.round(totalDuration.toNanos() / (double) numberOfEvents);
    }

    @Override
    public void prepare() {
      timestamp.set(0L);
    }

    @Override
    public void cleanup() {
      // Nothing to do
    }

    @Override
    public Long get() {
      return timestamp.getAndAdd(computeRandomStep(interval));
    }

    @Override
    public long getFirst() {
      return 0;
    }

    @Override
    public long getLast() {
      return timestamp.get();
    }

    protected abstract long computeRandomStep(long step);
  }

  private static final class ExponentialTimestampProvider extends MonotonicTimestampProvider {

    public ExponentialTimestampProvider(final Duration totalDuration, final int numberOfEvents) {
      super(totalDuration, numberOfEvents);
    }

    @Override
    protected long computeRandomStep(final long step) {
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
    protected long computeRandomStep(final long step) {
      return Math.round(
          Math.max(step + ((ThreadLocalRandom.current().nextGaussian() * step) * 1.0d), 0));
    }

    @Override
    public String toString() {
      return String.format("Gaussian: %d", interval);
    }
  }

  private abstract static class PregeneratedTimestampProvider implements TimestampProvider {

    protected static final int MARGIN = 100; // generate some extra events;
    private final AtomicInteger counter = new AtomicInteger(0);
    private long[] events;

    @Override
    public Long get() {
      return events[counter.getAndIncrement()];
    }

    protected abstract long[] generateEvents();

    @Override
    public void prepare() {
      events = generateEvents();
      counter.set(0);
    }

    @Override
    public void cleanup() {
      events = null;
    }

    @Override
    public long getFirst() {
      return events[0];
    }

    @Override
    public long getLast() {
      return events[counter.get()];
    }
  }

  private static final class UniformTimestampProvider extends PregeneratedTimestampProvider {

    private final Duration totalDuration;
    private final int totalEvents;

    UniformTimestampProvider(final Duration totalDuration, final int totalEvents) {
      this.totalDuration = totalDuration;
      this.totalEvents = totalEvents;
    }

    @Override
    public long[] generateEvents() {
      final Random random = new Random();
      return random
          .longs(totalEvents + MARGIN, 0, totalDuration.toNanos() + MARGIN)
          .sorted()
          .toArray();
    }

    @Override
    public String toString() {
      return "Uniform";
    }
  }

  private static final class ShortBurstsTimestampProvider extends PregeneratedTimestampProvider {

    private final int totalEvents;
    int eventsPerWindow;
    private final int burstPeriodWindows;
    private long[] events;

    ShortBurstsTimestampProvider(
        final int totalEvents, final int eventsPerWindow, final int burstPeriodWindows) {
      this.totalEvents = totalEvents;
      this.eventsPerWindow = eventsPerWindow;
      this.burstPeriodWindows = burstPeriodWindows;
    }

    @Override
    public long[] generateEvents() {
      final long burstSize = eventsPerWindow * burstPeriodWindows;

      events = new long[totalEvents + MARGIN];
      long timestamp = 0;
      int position = 0;
      while (position < events.length) {
        if (position % burstSize == 0) {
          timestamp += burstPeriodWindows * WINDOW_DURATION.toNanos();
        }
        events[position] = timestamp;
        timestamp++;
        position++;
      }
      return events;
    }

    @Override
    public String toString() {
      return "Short bursts";
    }
  }

  @ParameterizedTest(
      name =
          "{index} threadCount={0} timestamp={1} requestedEvents={2} requestedDuration={3} windowDuration={4} samplesPerWindow={5}")
  @MethodSource("samplerParams")
  public void testSampling(
      final int threadCount,
      final TimestampProvider timestampProvider,
      final int requestedEvents,
      final Duration requestedDuration,
      final Duration windowDuration,
      final int samplesPerWindow)
      throws InterruptedException {

    // Unfortunately @ParameterizedTest doesn't work in gradle
    LOGGER.info(
        "threadCount={} timestamp={} requestedEvents={} requestedDuration={} windowDuration={} samplesPerWindow={}",
        threadCount,
        timestampProvider,
        requestedEvents,
        requestedDuration,
        windowDuration,
        samplesPerWindow);

    try {
      timestampProvider.prepare();
      final StreamingSampler sampler =
          new StreamingSampler(
              windowDuration, samplesPerWindow, SAMPLER_LOOKBACK, timestampProvider);

      final long actualSamples = runThreadsAndCountSamples(sampler, threadCount, requestedEvents);

      final Duration actualDuration =
          Duration.ofNanos(timestampProvider.getLast() - timestampProvider.getFirst());

      final double durationDiscrepancy =
          (double) Math.abs(requestedDuration.toMillis() - actualDuration.toMillis())
              / requestedDuration.toMillis()
              * 100;

      final int startupBurstSize = StreamingSampler.CARRIED_OVER_ARRAY_SIZE * samplesPerWindow;
      final double expectedSamples =
          ((double) actualDuration.toNanos() / windowDuration.toNanos() * samplesPerWindow)
              + startupBurstSize;
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

      // TODO: Add edge case tests with hand crafted data (PROF-1289)
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
                  double tmp = ThreadLocalRandom.current().nextDouble();
                  for (long k = 0; k <= 10; k++) {
                    tmp = Math.cos(tmp);
                  }
                }
                totalSamplesCounter.addAndGet(samplesCount);
              });
      threads[j].start();
    }
    for (final Thread thread : threads) {
      thread.join();
    }

    return totalSamplesCounter.get();
  }

  private static Stream<Arguments> samplerParams() {
    // The way these tests are setup requires initial burst to be completely full filled in order
    // for tests to pass
    // This prevents us from running tests on very small total events - tests fail for undersampling
    // even though code works correctly
    // Test just doesn't take into account that we do 'feed' sampler enough data.
    final List<Integer> totalEvents = ImmutableList.of(50_000, 300_000, 1_000_000);
    final List<Integer> runDurations = ImmutableList.of(60, 120, 600);
    final List<Arguments> args = new ArrayList<>();
    for (int threadCount = 1; threadCount <= 64; threadCount *= 2) {
      for (int samplesPerWindow = 16; samplesPerWindow <= 256; samplesPerWindow *= 2) {
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
                    samplesPerWindow));
          }
        }
      }
    }

    /*
     * Tests with event bursts.
     * This test is special: running it wth many threads is impossible due to many threads capturing in parallel
     * events that otherwise would have belonged to bursts spread in time quite widely. This leads to concurrency issues
     * that are unrealistic in real life - mainly because different threads see vastly different time stamp in approximately same
     * physical time.
     * In fast this is a problem even with tests above - it just doesn't affect them often enough to cause failure.
     *
     * This is somewhat convoluted, but essentially `StreamingSampler.CARRIED_OVER_ARRAY_SIZE / N + 1` means that we skip
     * StreamingSampler.CARRIED_OVER_ARRAY_SIZE / N windows and make a burst on next one.
     * Skipping StreamingSampler.CARRIED_OVER_ARRAY_SIZE / N allows us to collect enough budget to capture all expected events.
     * FIXME: make this simpler
     */
    final int totalEventsForBursts = 1_000_000;
    for (int samples = 16; samples <= 256; samples *= 2) {
      args.add(
          Arguments.of(
              1,
              new ShortBurstsTimestampProvider(
                  totalEventsForBursts, samples, StreamingSampler.CARRIED_OVER_ARRAY_SIZE + 1),
              totalEventsForBursts,
              WINDOW_DURATION.multipliedBy(totalEventsForBursts / samples),
              WINDOW_DURATION,
              samples));
      args.add(
          Arguments.of(
              1,
              new ShortBurstsTimestampProvider(
                  totalEventsForBursts, samples, StreamingSampler.CARRIED_OVER_ARRAY_SIZE / 2 + 1),
              totalEventsForBursts,
              WINDOW_DURATION.multipliedBy(totalEventsForBursts / samples),
              WINDOW_DURATION,
              samples));
    }
    return args.stream();
  }
}
