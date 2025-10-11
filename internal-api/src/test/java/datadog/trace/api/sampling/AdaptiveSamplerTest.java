package datadog.trace.api.sampling;

import static java.lang.Math.abs;
import static java.lang.Math.round;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doNothing;

import datadog.trace.util.AgentTaskScheduler;
import datadog.trace.util.AgentTaskScheduler.Task;
import java.time.Duration;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;
import org.apache.commons.math3.distribution.PoissonDistribution;
import org.apache.commons.math3.stat.descriptive.moment.Mean;
import org.apache.commons.math3.stat.descriptive.moment.StandardDeviation;
import org.apache.commons.math3.util.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test various hand crafted scenarios of events coming in different patterns. Test both, the
 * isolated single threaded execution as well as events arriving on concurrent threads.
 *
 * <p>The test supports 'benchmark' mode to explore the reliability boundaries where all test cases
 * can be run multiple times - the number of iteration is passed in in {@literal
 * com.datadog.profiling.exceptions.test-iterations} system property.
 */
@ExtendWith(MockitoExtension.class)
class AdaptiveSamplerTest {
  private static final Logger log = LoggerFactory.getLogger(AdaptiveSamplerTest.class);
  private static final Duration WINDOW_DURATION = Duration.ofSeconds(1);

  /** Generates windows with numbers of events according to Poisson distribution */
  private static final class PoissonWindowEventsSupplier implements IntSupplier {
    private final PoissonDistribution distribution;

    /**
     * @param eventsPerWindowMean the average number of events per window
     */
    PoissonWindowEventsSupplier(final int eventsPerWindowMean) {
      distribution = new PoissonDistribution(eventsPerWindowMean);
      distribution.reseedRandomGenerator(12345671);
    }

    @Override
    public int getAsInt() {
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

  /**
   * Generates bursty windows - some of the windows have extremely low number of events while the
   * others have very hight number of events.
   */
  private static final class BurstingWindowsEventsSupplier implements IntSupplier {
    private final Random rnd = new Random(176431);

    private final double burstProbability;
    private final int minEvents;
    private final int maxEvents;

    /**
     * @param burstProbability the probability of burst window happening
     * @param nonBurstEvents number of events in non-burst window
     * @param burstEvents number of events in burst window
     */
    BurstingWindowsEventsSupplier(
        final double burstProbability, final int nonBurstEvents, final int burstEvents) {
      this.burstProbability = burstProbability;
      this.minEvents = nonBurstEvents;
      this.maxEvents = burstEvents;
    }

    @Override
    public int getAsInt() {
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

  /** Generates windows with constant number of events. */
  private static final class ConstantWindowsEventsSupplier implements IntSupplier {
    private final int events;

    /**
     * @param events number of events per window
     */
    ConstantWindowsEventsSupplier(final int events) {
      this.events = events;
    }

    @Override
    public int getAsInt() {
      return events;
    }

    @Override
    public String toString() {
      return "Constant: (" + "events=" + events + ')';
    }
  }

  /** Generates a pre-configured repeating sequence of window events */
  private static final class RepeatingWindowsEventsSupplier implements IntSupplier {
    private final int[] eventsCounts;
    private int pointer = 0;

    /**
     * @param windowEvents an array of number of events per each window in the sequence
     */
    RepeatingWindowsEventsSupplier(final int... windowEvents) {
      this.eventsCounts = Arrays.copyOf(windowEvents, windowEvents.length);
    }

    @Override
    public int getAsInt() {
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

  private static class WindowSamplingResult {
    final int events;
    final int samples;
    final double sampleIndexSkew;

    WindowSamplingResult(int events, int samples, double sampleIndexSkew) {
      this.events = events;
      this.samples = samples;
      this.sampleIndexSkew = sampleIndexSkew;
    }
  }

  private static final StandardDeviation STANDARD_DEVIATION = new StandardDeviation();
  private static final int WINDOWS = 120;
  private static final int SAMPLES_PER_WINDOW = 100;
  private static final int AVERAGE_LOOKBACK = 30;
  private static final int BUDGET_LOOKBACK = 16;

  @Mock AgentTaskScheduler taskScheduler;
  @Captor ArgumentCaptor<Task<AdaptiveSampler>> rollWindowTaskCaptor;
  @Captor ArgumentCaptor<AdaptiveSampler> rollWindowTargetCaptor;

  @BeforeEach
  public void setup() {
    doNothing()
        .when(taskScheduler)
        .weakScheduleAtFixedRate(
            rollWindowTaskCaptor.capture(),
            rollWindowTargetCaptor.capture(),
            eq(WINDOW_DURATION.toNanos()),
            eq(WINDOW_DURATION.toNanos()),
            same(TimeUnit.NANOSECONDS));
  }

  @Test
  public void testBurstLowProbability() throws Exception {
    testSampler(new BurstingWindowsEventsSupplier(0.1d, 5, 5000), 40);
  }

  @Test
  public void testBurstHighProbability() throws Exception {
    testSampler(new BurstingWindowsEventsSupplier(0.8d, 5, 5000), 20);
  }

  @Test
  public void testPoissonLowFrequency() throws Exception {
    testSampler(new PoissonWindowEventsSupplier(153), 15);
  }

  @Test
  public void testPoissonMidFrequency() throws Exception {
    testSampler(new PoissonWindowEventsSupplier(283), 15);
  }

  @Test
  public void testPoissonHighFrequency() throws Exception {
    testSampler(new PoissonWindowEventsSupplier(1013), 15);
  }

  @Test
  public void testConstantVeryLowLoad() throws Exception {
    testSampler(new ConstantWindowsEventsSupplier(1), 10);
  }

  @Test
  public void testConstantLowLoad() throws Exception {
    testSampler(new ConstantWindowsEventsSupplier(153), 15);
  }

  @Test
  public void testConstantMediumLoad() throws Exception {
    testSampler(new ConstantWindowsEventsSupplier(713), 15);
  }

  @Test
  public void testConstantHighLoad() throws Exception {
    testSampler(new ConstantWindowsEventsSupplier(5211), 15);
  }

  @Test
  public void testRepeatingSemiRandom() throws Exception {
    testSampler(
        new RepeatingWindowsEventsSupplier(180, 200, 0, 0, 0, 1500, 1000, 430, 200, 115, 115, 900),
        15);
  }

  @Test
  public void testRepeatingRegularStartWithBurst() throws Exception {
    testSampler(new RepeatingWindowsEventsSupplier(1000, 0, 1000, 0, 1000, 0), 15);
  }

  @Test
  public void testRepeatingRegularStartWithLow() throws Exception {
    testSampler(new RepeatingWindowsEventsSupplier(0, 1000, 0, 1000, 0, 1000), 15);
  }

  @Test
  public void testKeep() {
    final AdaptiveSampler sampler =
        new AdaptiveSampler(WINDOW_DURATION, 1, 1, 1, null, taskScheduler, true);
    long tests = sampler.testCount();
    long samples = sampler.sampleCount();
    assertTrue(sampler.keep());
    assertEquals(tests + 1, sampler.testCount());
    assertEquals(samples + 1, sampler.sampleCount());
  }

  @Test
  public void testDrop() {
    final AdaptiveSampler sampler =
        new AdaptiveSampler(WINDOW_DURATION, 1, 1, 1, null, taskScheduler, true);
    long tests = sampler.testCount();
    long samples = sampler.sampleCount();
    assertFalse(sampler.drop());
    assertEquals(tests + 1, sampler.testCount());
    assertEquals(samples, sampler.sampleCount());
  }

  @Test
  void testConfigListener() throws Exception {
    AtomicInteger counter = new AtomicInteger(0);
    final AdaptiveSampler sampler =
        new AdaptiveSampler(
            WINDOW_DURATION,
            2,
            1,
            1,
            (totalCount, sampledCount, budget, totalAverage, probability) -> {
              switch (counter.getAndIncrement()) {
                case 0:
                  {
                    // initial config at the sampler instantiation
                    assertEquals(0, totalCount);
                    assertEquals(0, sampledCount);
                    assertEquals(4, budget);
                    assertEquals(0.0d, totalAverage);
                    assertEquals(1.0d, probability);
                    break;
                  }
                case 1:
                  {
                    // after first roll window
                    assertEquals(2, totalCount);
                    assertEquals(1, sampledCount);
                    assertEquals(1, budget);
                    assertEquals(2.0d, totalAverage);
                    assertEquals(0.5d, probability);
                    break;
                  }
                case 2:
                  {
                    // after second roll window
                    assertEquals(3, totalCount);
                    assertEquals(2, sampledCount);
                    assertEquals(0, budget);
                    assertEquals(3.0d, totalAverage);
                    assertEquals(0.0d, probability);
                    break;
                  }
                case 3:
                  {
                    // after third roll window
                    assertEquals(3, totalCount);
                    assertEquals(0, sampledCount);
                    assertEquals(2, budget);
                    assertEquals(3.0d, totalAverage);
                    assertEquals(0.6666d, probability, 0.00007d);
                    System.err.println(
                        "==> "
                            + totalCount
                            + ", "
                            + sampledCount
                            + ", "
                            + budget
                            + ", "
                            + totalAverage
                            + ", "
                            + probability);
                    break;
                  }
              }
            },
            taskScheduler,
            true);
    sampler.keep();
    sampler.drop();
    rollWindow();
    sampler.keep();
    sampler.keep();
    sampler.drop();
    rollWindow();
    sampler.drop();
    sampler.drop();
    sampler.drop();
    rollWindow();
  }

  private void testSampler(final IntSupplier windowEventsSupplier, final int maxErrorPercent)
      throws Exception {
    int iterations =
        Integer.parseInt(
            System.getProperty("com.datadog.profiling.exceptions.test-iterations", "1"));
    for (int i = 0; i < iterations; i++) {
      testSamplerInline(windowEventsSupplier, maxErrorPercent);
      for (int numOfThreads = 1; numOfThreads <= 64; numOfThreads *= 2) {
        testSamplerConcurrently(numOfThreads, windowEventsSupplier, maxErrorPercent);
      }
    }
  }

  private void testSamplerInline(
      final IntSupplier windowEventsSupplier, final int maxErrorPercent) {
    log.info(
        "> mode: {}, windows: {}, SAMPLES_PER_WINDOW: {}, LOOKBACK: {}, max error: {}%",
        windowEventsSupplier, WINDOWS, SAMPLES_PER_WINDOW, AVERAGE_LOOKBACK, maxErrorPercent);
    final AdaptiveSampler sampler =
        new AdaptiveSampler(
            WINDOW_DURATION,
            SAMPLES_PER_WINDOW,
            AVERAGE_LOOKBACK,
            BUDGET_LOOKBACK,
            null,
            taskScheduler,
            true);

    // simulate event generation and sampling for the given number of sampling windows
    final long expectedSamples = WINDOWS * SAMPLES_PER_WINDOW;

    long allSamples = 0L;
    long allEvents = 0L;

    final double[] samplesPerWindow = new double[WINDOWS];
    final double[] sampleIndexSkewPerWindow = new double[WINDOWS];
    final Mean mean = new Mean();
    for (int w = 0; w < WINDOWS; w++) {
      final long samplesBase = 0L;
      WindowSamplingResult result =
          generateWindowEventsAndSample(windowEventsSupplier, sampler, mean);
      samplesPerWindow[w] =
          (1 - abs((result.samples - samplesBase - expectedSamples) / (double) expectedSamples));
      sampleIndexSkewPerWindow[w] = result.sampleIndexSkew;
      allSamples += result.samples;
      allEvents += result.events;

      rollWindow();
    }

    /*
     * Turn all events into samples if their number is <= than the expected number of samples.
     */
    final double targetSamples = Math.min(allEvents, expectedSamples);

    /*
     * Calculate the percentual error based on the expected and the observed number of samples.
     */
    final double percentualError = round(((targetSamples - allSamples) / targetSamples) * 100);

    reportSampleStatistics(samplesPerWindow, targetSamples, percentualError);
    reportSampleIndexSkew(sampleIndexSkewPerWindow);

    assertTrue(
        abs(percentualError) <= maxErrorPercent,
        "abs(("
            + targetSamples
            + " - "
            + allSamples
            + ") / "
            + targetSamples
            + ")% > "
            + maxErrorPercent
            + "%");
  }

  private void reportSampleStatistics(
      double[] samplesPerWindow, double targetSamples, double percentualError) {
    final double samplesPerWindowMean = new Mean().evaluate(samplesPerWindow);
    final double samplesPerWindowStdev =
        STANDARD_DEVIATION.evaluate(samplesPerWindow, samplesPerWindowMean);

    log.info(
        "\t per window samples = (avg: {}, stdev: {}, estimated total: {})",
        samplesPerWindowMean,
        samplesPerWindowStdev,
        targetSamples);

    log.info("\t percentual error = {}%", percentualError);
  }

  private void reportSampleIndexSkew(double[] sampleIndexSkewPerWindow) {
    Pair<Double, Double> skewIndicators = calculateSkewIndicators(sampleIndexSkewPerWindow);
    log.info(
        "\t avg window skew interval = <-{}%, {}%>",
        round(skewIndicators.getFirst() * 100), round(skewIndicators.getSecond() * 100));
  }

  /**
   * Simulate the number of events per window. Perform sampling and capture the number of observed
   * events and samples.
   *
   * @param windowEventsSupplier events generator implementation
   * @param sampler sampler instance
   * @param mean a {@code Mean} instance for calculations
   * @return a {@linkplain WindowSamplingResult} instance capturing the number of observed events,
   *     samples and the sample index skew
   */
  private WindowSamplingResult generateWindowEventsAndSample(
      IntSupplier windowEventsSupplier, AdaptiveSampler sampler, Mean mean) {
    int samples = 0;
    int events = windowEventsSupplier.getAsInt();
    mean.clear();
    for (int i = 0; i < events; i++) {
      if (sampler.sample()) {
        mean.increment(i);
        samples++;
      }
    }
    double sampleIndexMean = mean.getResult();
    double sampleIndexSkew = events != 0 ? sampleIndexMean / events : 0;
    return new WindowSamplingResult(events, samples, sampleIndexSkew);
  }

  /**
   * Calculate the sample index skew boundaries. A 'sample index skew' is defined as the distance of
   * the average sample index in each window from the mean event index in the same window. Given the
   * range of the event indices 1..N, the event index mean M calculated as (N - 1)/2 and the sample
   * index mean S the skew K is calculated as 'K = M - S'. This gives the skew range of &lt;-0.5,
   * 0.5&gt;.
   *
   * <p>If the samples are spread out completely regularly the skew would be 0. If the beginning of
   * the window is favored the skew would be negative and if the tail of the window is favored the
   * skew would be positive.
   *
   * @param sampleIndexSkewPerWindow the index skew per window
   * @return a min-max boundaries for the sample index skew
   */
  private Pair<Double, Double> calculateSkewIndicators(double[] sampleIndexSkewPerWindow) {
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
    return new Pair<>(skewNegativeAvg, skewPositiveAvg);
  }

  private void testSamplerConcurrently(
      final int threadCount, final IntSupplier windowEventsSupplier, final int maxErrorPercent)
      throws Exception {
    log.info(
        "> threads: {}, mode: {}, windows: {}, SAMPLES_PER_WINDOW: {}, LOOKBACK: {}, max error: {}",
        threadCount,
        windowEventsSupplier,
        WINDOWS,
        SAMPLES_PER_WINDOW,
        AVERAGE_LOOKBACK,
        maxErrorPercent);

    /*
     * This test attempts to simulate concurrent computations by making sure that sampling requests and the window maintenance routine are run in parallel.
     * It does not provide coverage of all possible execution sequences but should be good enough for getting the 'ballpark' numbers.
     */
    final long expectedSamples = SAMPLES_PER_WINDOW * WINDOWS;
    final AtomicLong allSamples = new AtomicLong(0);
    final AtomicLong receivedEvents = new AtomicLong(0);

    final AdaptiveSampler sampler =
        new AdaptiveSampler(
            WINDOW_DURATION,
            SAMPLES_PER_WINDOW,
            AVERAGE_LOOKBACK,
            BUDGET_LOOKBACK,
            null,
            taskScheduler,
            true);
    final CyclicBarrier startBarrier = new CyclicBarrier(threadCount);
    final CyclicBarrier endBarrier = new CyclicBarrier(threadCount, this::rollWindow);
    final Mean[] means = new Mean[threadCount];
    final Thread[] threads = new Thread[threadCount];
    for (int i = 0; i < threadCount; i++) {
      means[i] = new Mean();
      final Mean mean = means[i];
      threads[i] =
          new Thread(
              () -> {
                try {
                  for (int w = 0; w < WINDOWS; w++) {
                    startBarrier.await(10, TimeUnit.SECONDS);
                    WindowSamplingResult samplingResult =
                        generateWindowEventsAndSample(windowEventsSupplier, sampler, mean);
                    allSamples.addAndGet(samplingResult.samples);
                    receivedEvents.addAndGet(samplingResult.events);
                    endBarrier.await(10, TimeUnit.SECONDS);
                  }
                } catch (Throwable ignored) {
                }
              });
      threads[i].start();
    }
    for (final Thread t : threads) {
      t.join();
    }

    final long samples = allSamples.get();
    /*
     * Turn all events into samples if their number is <= than the expected number of samples.
     */
    final long targetSamples = Math.min(expectedSamples, receivedEvents.get());
    /*
     * Calculate the percentual error based on the expected and the observed number of samples.
     */
    final int percentualError = round(((targetSamples - samples) / (float) targetSamples) * 100);
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

  private void rollWindow() {
    rollWindowTaskCaptor.getValue().run(rollWindowTargetCaptor.getValue());
  }
}
