package com.datadog.profiling.exceptions;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Phaser;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.apache.commons.math3.distribution.PoissonDistribution;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class StreamingSamplerTest {

  private static final Logger LOGGER = LoggerFactory.getLogger(StreamingSamplerTest.class);

  private static final class PoissonWindowEventsSupplier implements Supplier<Integer> {
    private final PoissonDistribution distribution;

    PoissonWindowEventsSupplier(int eventsPerWindowMean) {
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

    BurstingWindowsEventsSupplier(double burstProbability, int minEvents, int maxEvents) {
      this.burstProbability = burstProbability;
      this.minEvents = minEvents;
      this.maxEvents = maxEvents;
    }

    @Override
    public Integer get() {
      return rnd.nextDouble() <= burstProbability ? maxEvents : minEvents;
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

    ConstantWindowsEventsSupplier(int events) {
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

    RepeatingWindowsEventsSupplier(int... eventsCounts) {
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

  @ParameterizedTest
  @MethodSource("samplerParams")
  public void testSampler(
      Supplier<Integer> windowEventsSupplier,
      int windows,
      int samplesPerWindow,
      int lookback,
      int maxErrorPercent)
      throws Exception {
    LOGGER.info(
        "> mode: {}, windows: {}, samplesPerWindow: {}, lookback: {}",
        windowEventsSupplier,
        windows,
        samplesPerWindow,
        lookback);
    StreamingSampler instance =
        new StreamingSampler(Duration.ofSeconds(1), samplesPerWindow, lookback, false);

    long expectedSamples = windows * samplesPerWindow;

    long samples = 0L;

    int[] totalParts = new int[windows];
    double[] sampledParts = new double[windows];
    double[] sampleIndexSkew = new double[windows];
    for (int w = 0; w < windows; w++) {
      List<Integer> sampleIndices = new ArrayList<>();
      long samplesBase = samples;
      int events = windowEventsSupplier.get();
      for (int i = 0; i < events; i++) {
        if (instance.sample()) {
          sampleIndices.add(i);
          samples++;
        }
      }
      totalParts[w] = events;
      sampledParts[w] =
          (1 - Math.abs((samples - samplesBase - expectedSamples) / (double) expectedSamples));

      double sampleIndexMean = calculateMean(sampleIndices);
      sampleIndexSkew[w] = events != 0 ? sampleIndexMean / events : 0;
      instance.rollWindow();
    }
    double sampledPartMean = calculateMean(sampledParts);
    double sampledPartStdev = calculateStddev(sampledParts, sampledPartMean);
    double totalPartMean = calculateMean(totalParts);

    double correctionFactor = Math.min(((totalPartMean * windows) / expectedSamples), 1);
    double targetSamples = expectedSamples * correctionFactor;
    double percentualError =
        Math.round(Math.abs(((targetSamples - samples) / targetSamples)) * 100);

    double skewPositiveAvg = 0d;
    double skewNegativeAvg = 0d;
    int negativeCount = 0;
    for (double skew : sampleIndexSkew) {
      if (skew >= 0.5d) {
        skewPositiveAvg += skew - 0.5d;
      } else {
        negativeCount++;
        skewNegativeAvg += 0.5d - skew;
      }
    }
    int positiveCount = sampleIndexSkew.length - negativeCount;
    if (positiveCount > 0) {
      skewPositiveAvg /= sampleIndexSkew.length - negativeCount;
    }
    if (negativeCount > 0) {
      skewNegativeAvg /= negativeCount;
    }

    LOGGER.info(
        "\t per window samples = (avg: {}, stdev: {}, estimated total: {}",
        sampledPartMean * expectedSamples,
        sampledPartStdev * expectedSamples,
        (sampledPartMean * windows) / correctionFactor + ")");
    LOGGER.info(
        "\t avg window skew interval = <-{}%, {}%>",
        Math.round(skewNegativeAvg * 100), Math.round(skewPositiveAvg * 100));
    LOGGER.info("\t percentual error = {}%", percentualError);

    assertTrue(
        percentualError <= maxErrorPercent,
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

  @ParameterizedTest
  @MethodSource("samplerParamsConcurrency")
  public void testSamplerConcurrency(
      int threadCount,
      Supplier<Integer> windowEventsSupplier,
      int windows,
      int samplesPerWindow,
      int lookback,
      int maxErrorPercent)
      throws Exception {
    LOGGER.info(
        "> threads: {}, mode: {}, windows: {}, samplesPerWindow: {}, lookback: {}",
        threadCount,
        windowEventsSupplier,
        windows,
        samplesPerWindow,
        lookback);

    /*
     * This test attempts to simulate concurrent computations by making sure that sampling requests and the window maintenance routine are run in parallel.
     * It does not provide coverage of all possible execution sequences but should be good enough for getting the 'ballpark' numbers.
     */

    long expectedSamples = samplesPerWindow * windows;
    AtomicLong allSamples = new AtomicLong(0);
    Thread[] threads = new Thread[threadCount];

    Phaser phaser = new Phaser(threadCount + 1);

    StreamingSampler instance =
        new StreamingSampler(Duration.ofSeconds(1), samplesPerWindow, lookback, false);

    for (int i = 0; i < threadCount; i++) {
      threads[i] =
          new Thread(
              () -> {
                for (int w = 0; w < windows; w++) {
                  int events = windowEventsSupplier.get();
                  for (int e = 0; e < events; e++) {
                    if (instance.sample()) {
                      allSamples.incrementAndGet();
                    }
                  }
                  /*
                   * Block here until window roll is initiated from other thread.
                   * After the roll has been started the next window data starts arriving in parallel.
                   */
                  phaser.arriveAndAwaitAdvance();
                }
              });
    }

    Thread roller =
        new Thread(
            () -> {
              while (!Thread.currentThread().isInterrupted()) {
                // wait for the next window signalled from the data generating thread before
                // starting to roll the window
                phaser.arriveAndAwaitAdvance();
                instance.rollWindow();
              }
            });
    roller.start();
    for (Thread t : threads) {
      t.start();
    }
    for (Thread t : threads) {
      t.join();
    }

    roller.interrupt();

    long samples = allSamples.get();
    int percentualError =
        Math.round(Math.abs(((expectedSamples - samples) / (float) expectedSamples)) * 100);
    LOGGER.info("\t percentual error = {}%", percentualError);

    assertTrue(
        percentualError <= maxErrorPercent,
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

  private double calculateStddev(double[] data, double average) {
    if (data.length == 0) {
      return 0;
    }
    double stdev = 0d;
    for (double item : data) {
      stdev += Math.pow(item - average, 2);
    }
    stdev = Math.sqrt(stdev / data.length);
    return stdev;
  }

  private double calculateMean(double[] data) {
    if (data.length == 0) {
      return 0;
    }

    double mean = 0d;
    for (double item : data) {
      mean += item;
    }
    mean /= data.length;
    return mean;
  }

  private double calculateMean(int[] data) {
    if (data.length == 0) {
      return 0;
    }

    double mean = 0d;
    for (double item : data) {
      mean += item;
    }
    mean /= data.length;
    return mean;
  }

  private double calculateMean(List<Integer> data) {
    if (data.isEmpty()) {
      return 0;
    }

    double mean = 0d;
    for (double item : data) {
      mean += item;
    }
    mean /= data.size();
    return mean;
  }

  private static Stream<Arguments> samplerParams() {
    int windows = 60;
    int samplesPerWindow = 200;
    int lookback = 60;
    return Stream.of(
        Arguments.of(
            new BurstingWindowsEventsSupplier(0.1d, 10, 5000),
            windows,
            samplesPerWindow,
            lookback,
            10),
        Arguments.of(
            new BurstingWindowsEventsSupplier(0.8d, 10, 5000),
            windows,
            samplesPerWindow,
            lookback,
            5),
        Arguments.of(new PoissonWindowEventsSupplier(150), windows, samplesPerWindow, lookback, 0),
        Arguments.of(new PoissonWindowEventsSupplier(250), windows, samplesPerWindow, lookback, 2),
        Arguments.of(new PoissonWindowEventsSupplier(1500), windows, samplesPerWindow, lookback, 2),
        Arguments.of(new ConstantWindowsEventsSupplier(5), windows, samplesPerWindow, lookback, 0),
        Arguments.of(
            new ConstantWindowsEventsSupplier(120), windows, samplesPerWindow, lookback, 2),
        Arguments.of(
            new ConstantWindowsEventsSupplier(250), windows, samplesPerWindow, lookback, 2),
        Arguments.of(
            new ConstantWindowsEventsSupplier(3000), windows, samplesPerWindow, lookback, 2),
        Arguments.of(
            new RepeatingWindowsEventsSupplier(
                180, 200, 0, 0, 0, 1500, 1000, 430, 200, 115, 115, 900),
            windows,
            samplesPerWindow,
            lookback,
            2));
  }

  private static Stream<Arguments> samplerParamsConcurrency() {
    int windows = 600;
    int samplesPerWindow = 20;
    int lookback = 60;
    return Stream.of(
        Arguments.of(
            1,
            new BurstingWindowsEventsSupplier(0.1d, 1, 500),
            windows,
            samplesPerWindow,
            lookback,
            5),
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
            1, new ConstantWindowsEventsSupplier(25), windows, samplesPerWindow, lookback, 5),
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
            5));
  }
}
