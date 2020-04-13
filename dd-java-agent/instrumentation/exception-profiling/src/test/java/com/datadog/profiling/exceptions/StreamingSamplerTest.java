package com.datadog.profiling.exceptions;

import static java.lang.Math.abs;
import static java.lang.Math.min;
import static java.lang.Math.round;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.when;

import datadog.common.exec.CommonTaskExecutor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.distribution.PoissonDistribution;
import org.apache.commons.math3.stat.descriptive.moment.Mean;
import org.apache.commons.math3.stat.descriptive.moment.StandardDeviation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@Slf4j
class StreamingSamplerTest {

  private static final Duration WINDOW_DURATION = Duration.ofSeconds(1);

  private static final class PoissonWindowEventsSupplier implements Supplier<Integer> {
    private final PoissonDistribution distribution;

    PoissonWindowEventsSupplier(final int eventsPerWindowMean) {
      distribution = new PoissonDistribution(eventsPerWindowMean);
      distribution.reseedRandomGenerator(12345671);
    }

    @Override
    public Integer get() {
      return distribution.sample();
    }

    @Override
    public String toString() {
      return "Poisson: ("
          + "mean="
          + distribution.getMean()
          + ", variance="
          + distribution.getNumericalVariance()
          + ")";
    }
  }

  private static final class BurstingWindowsEventsSupplier implements Supplier<Integer> {
    private final Random rnd = new Random(176431);

    private final double burstProbability;
    private final int minEvents;
    private final int maxEvents;

    BurstingWindowsEventsSupplier(
        final double burstProbability, final int minEvents, final int maxEvents) {
      this.burstProbability = burstProbability;
      this.minEvents = minEvents;
      this.maxEvents = maxEvents;
    }

    @Override
    public Integer get() {
      if (rnd.nextDouble() <= burstProbability) {
        return maxEvents;
      } else {
        return minEvents;
      }
    }

    @Override
    public String toString() {
      return "Burst: ("
          + "probability="
          + burstProbability
          + ", minEvents="
          + minEvents
          + ", maxEvents="
          + maxEvents
          + ')';
    }
  }

  private static final class ConstantWindowsEventsSupplier implements Supplier<Integer> {
    private final int events;

    ConstantWindowsEventsSupplier(final int events) {
      this.events = events;
    }

    @Override
    public Integer get() {
      return events;
    }

    @Override
    public String toString() {
      return "Constant: (" + "events=" + events + ')';
    }
  }

  private static final class RepeatingWindowsEventsSupplier implements Supplier<Integer> {
    private final int[] eventsCounts;
    private int pointer = 0;

    RepeatingWindowsEventsSupplier(final int... eventsCounts) {
      this.eventsCounts = Arrays.copyOf(eventsCounts, eventsCounts.length);
    }

    @Override
    public Integer get() {
      try {
        return eventsCounts[pointer];
      } finally {
        pointer = (pointer + 1) % eventsCounts.length;
      }
    }

    @Override
    public String toString() {
      return "Repeating: (" + "definition=" + Arrays.toString(eventsCounts) + ')';
    }
  }

  private static final StandardDeviation STANDARD_DEVIATION = new StandardDeviation();
  private static final Mean MEAN = new Mean();

  @Mock CommonTaskExecutor taskExecutor;
  @Captor ArgumentCaptor<Runnable> rollWindowCaptor;
  @Mock ScheduledFuture scheduledFuture;

  @BeforeEach
  public void setup() {
    when(taskExecutor.scheduleAtFixedRate(
            rollWindowCaptor.capture(),
            eq(WINDOW_DURATION.toNanos()),
            eq(WINDOW_DURATION.toNanos()),
            same(TimeUnit.NANOSECONDS)))
        .thenReturn(scheduledFuture);
  }

  @ParameterizedTest
  @MethodSource("samplerParams")
  public void testSampler(
      final Supplier<Integer> windowEventsSupplier,
      final int windows,
      final int samplesPerWindow,
      final int lookback,
      final int maxErrorPercent) {
    log.info(
        "> mode: {}, windows: {}, samplesPerWindow: {}, lookback: {}, max error: {}%",
        windowEventsSupplier, windows, samplesPerWindow, lookback, maxErrorPercent);
    final StreamingSampler instance =
        new StreamingSampler(WINDOW_DURATION, samplesPerWindow, lookback, taskExecutor);

    final long expectedSamples = windows * samplesPerWindow;

    long samples = 0L;

    final double[] totalEventsPerWindow = new double[windows];
    final double[] sampledEventsPerWindow = new double[windows];
    final double[] sampleIndexSkewPerWindow = new double[windows];
    for (int w = 0; w < windows; w++) {
      final List<Integer> sampleIndices = new ArrayList<>();
      final long samplesBase = samples;
      final int events = windowEventsSupplier.get();
      for (int i = 0; i < events; i++) {
        if (instance.sample()) {
          sampleIndices.add(i);
          samples++;
        }
      }
      totalEventsPerWindow[w] = events;
      sampledEventsPerWindow[w] =
          (1 - abs((samples - samplesBase - expectedSamples) / (double) expectedSamples));

      final double sampleIndexMean = MEAN.evaluate(toDoubleArray(sampleIndices));
      sampleIndexSkewPerWindow[w] = events != 0 ? sampleIndexMean / events : 0;
      rollWindowCaptor.getValue().run();
    }
    final double sampledEventsPerWindowMean = MEAN.evaluate(sampledEventsPerWindow);
    final double sampledEventsPerWindowStddev =
        STANDARD_DEVIATION.evaluate(sampledEventsPerWindow, sampledEventsPerWindowMean);
    final double totalEventsPerWindowMean = MEAN.evaluate(totalEventsPerWindow);

    final double correctionFactor =
        min(((totalEventsPerWindowMean * windows) / expectedSamples), 1);
    final double targetSamples = expectedSamples * correctionFactor;
    final double percentualError = round(((targetSamples - samples) / targetSamples) * 100);

    double skewPositiveAvg = 0d;
    double skewNegativeAvg = 0d;
    int negativeCount = 0;
    for (final double skew : sampleIndexSkewPerWindow) {
      if (skew >= 0.5d) {
        skewPositiveAvg += skew - 0.5d;
      } else {
        negativeCount++;
        skewNegativeAvg += 0.5d - skew;
      }
    }
    final int positiveCount = sampleIndexSkewPerWindow.length - negativeCount;
    if (positiveCount > 0) {
      skewPositiveAvg /= sampleIndexSkewPerWindow.length - negativeCount;
    }
    if (negativeCount > 0) {
      skewNegativeAvg /= negativeCount;
    }

    log.info(
        "\t per window samples = (avg: {}, stdev: {}, estimated total: {}",
        sampledEventsPerWindowMean * expectedSamples,
        sampledEventsPerWindowStddev * expectedSamples,
        (sampledEventsPerWindowMean * windows) / correctionFactor + ")");
    log.info(
        "\t avg window skew interval = <-{}%, {}%>",
        round(skewNegativeAvg * 100), round(skewPositiveAvg * 100));
    log.info("\t percentual error = {}%", percentualError);

    assertTrue(
        abs(percentualError) <= maxErrorPercent,
        "abs(("
            + targetSamples
            + " - "
            + samples
            + ") / "
            + targetSamples
            + ")% > "
            + maxErrorPercent
            + "%");
  }

  private static double[] toDoubleArray(final List<? extends Number> data) {
    return data.stream().mapToDouble(Number::doubleValue).toArray();
  }

  @ParameterizedTest
  @MethodSource("samplerParamsConcurrency")
  public void testSamplerConcurrency(
      final int threadCount,
      final Supplier<Integer> windowEventsSupplier,
      final int windows,
      final int samplesPerWindow,
      final int lookback,
      final int maxErrorPercent)
      throws Exception {
    log.info(
        "> threads: {}, mode: {}, windows: {}, samplesPerWindow: {}, lookback: {}, max error: {}",
        threadCount,
        windowEventsSupplier,
        windows,
        samplesPerWindow,
        lookback,
        maxErrorPercent);

    /*
     * This test attempts to simulate concurrent computations by making sure that sampling requests and the window maintenance routine are run in parallel.
     * It does not provide coverage of all possible execution sequences but should be good enough for getting the 'ballpark' numbers.
     */

    final long expectedSamples = samplesPerWindow * windows;
    final AtomicLong allSamples = new AtomicLong(0);

    final StreamingSampler instance =
        new StreamingSampler(WINDOW_DURATION, samplesPerWindow, lookback, taskExecutor);

    for (int w = 0; w < windows; w++) {
      final Thread[] threads = new Thread[threadCount];
      for (int i = 0; i < threadCount; i++) {
        threads[i] =
            new Thread(
                () -> {
                  final int events = windowEventsSupplier.get();
                  for (int e = 0; e < events; e++) {
                    if (instance.sample()) {
                      allSamples.incrementAndGet();
                    }
                  }
                });
      }

      for (final Thread t : threads) {
        t.start();
      }
      for (final Thread t : threads) {
        t.join();
      }
      rollWindowCaptor.getValue().run();
    }

    final long samples = allSamples.get();
    final int percentualError =
        round(((expectedSamples - samples) / (float) expectedSamples) * 100);
    log.info("\t percentual error = {}%", percentualError);

    assertTrue(
        abs(percentualError) <= maxErrorPercent,
        "abs(("
            + expectedSamples
            + " - "
            + samples
            + ") / "
            + expectedSamples
            + ")% > "
            + maxErrorPercent
            + "%");
  }

  private static Stream<Arguments> samplerParams() {
    final int windows = 60;
    final int samplesPerWindow = 200;
    final int lookback = 10;
    return Stream.of(
        Arguments.of(
            new BurstingWindowsEventsSupplier(0.1d, 10, 5000),
            windows,
            samplesPerWindow,
            lookback,
            30),
        Arguments.of(
            new BurstingWindowsEventsSupplier(0.8d, 10, 5000),
            windows,
            samplesPerWindow,
            lookback,
            15),
        Arguments.of(new PoissonWindowEventsSupplier(150), windows, samplesPerWindow, lookback, 0),
        Arguments.of(new PoissonWindowEventsSupplier(253), windows, samplesPerWindow, lookback, 2),
        Arguments.of(new PoissonWindowEventsSupplier(1507), windows, samplesPerWindow, lookback, 8),
        Arguments.of(new ConstantWindowsEventsSupplier(5), windows, samplesPerWindow, lookback, 0),
        Arguments.of(
            new ConstantWindowsEventsSupplier(120), windows, samplesPerWindow, lookback, 2),
        Arguments.of(
            new ConstantWindowsEventsSupplier(253), windows, samplesPerWindow, lookback, 2),
        Arguments.of(
            new ConstantWindowsEventsSupplier(3001), windows, samplesPerWindow, lookback, 15),
        Arguments.of(
            new RepeatingWindowsEventsSupplier(
                180, 200, 0, 0, 0, 1500, 1000, 430, 200, 115, 115, 900),
            windows,
            samplesPerWindow,
            lookback,
            2),
        Arguments.of(
            new RepeatingWindowsEventsSupplier(1000, 0, 1000, 0, 1000, 0),
            windows,
            samplesPerWindow,
            lookback,
            10));
  }

  private static Stream<Arguments> samplerParamsConcurrency() {
    final int windows = 600;
    final int samplesPerWindow = 20;
    final int lookback = 60;
    return Stream.of(
        Arguments.of(
            1,
            new BurstingWindowsEventsSupplier(0.1d, 1, 500),
            windows,
            samplesPerWindow,
            lookback,
            30),
        Arguments.of(
            16,
            new BurstingWindowsEventsSupplier(0.1d, 1, 500),
            windows,
            samplesPerWindow,
            lookback,
            5),
        Arguments.of(
            1, new PoissonWindowEventsSupplier(150), windows, samplesPerWindow, lookback, 5),
        Arguments.of(
            16, new PoissonWindowEventsSupplier(150), windows, samplesPerWindow, lookback, 5),
        Arguments.of(
            1, new ConstantWindowsEventsSupplier(25), windows, samplesPerWindow, lookback, 10),
        Arguments.of(
            16, new ConstantWindowsEventsSupplier(25), windows, samplesPerWindow, lookback, 5),
        Arguments.of(
            1,
            new RepeatingWindowsEventsSupplier(18, 20, 0, 0, 0, 150, 100, 43, 20, 11, 12, 90),
            windows,
            samplesPerWindow,
            lookback,
            5),
        Arguments.of(
            16,
            new RepeatingWindowsEventsSupplier(18, 20, 0, 0, 0, 150, 100, 43, 20, 11, 12, 90),
            windows,
            samplesPerWindow,
            lookback,
            5),
        Arguments.of(
            1,
            new RepeatingWindowsEventsSupplier(1000, 0, 1000, 0, 1000, 0),
            windows,
            samplesPerWindow,
            lookback,
            5),
        Arguments.of(
            16,
            new RepeatingWindowsEventsSupplier(1000, 0, 1000, 0, 1000, 0),
            windows,
            samplesPerWindow,
            lookback,
            5));
  }
}
