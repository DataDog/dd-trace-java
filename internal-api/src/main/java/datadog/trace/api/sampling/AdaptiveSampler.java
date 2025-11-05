package datadog.trace.api.sampling;

import datadog.trace.util.AgentTaskScheduler;
import datadog.trace.util.AgentTaskScheduler.Task;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nullable;

/**
 * An adaptive streaming (non-remembering) sampler.
 *
 * <p>The sampler attempts to generate at most N samples per fixed time window in randomized
 * fashion. For this it divides the timeline into 'sampling windows' of constant duration. Each
 * sampling window targets a constant number of samples which are scattered randomly (uniform
 * distribution) throughout the window duration and once the window is over the real stats of
 * incoming events and the number of gathered samples is used to recompute the target probability to
 * use in the following window.
 *
 * <p>This will guarantee, if the windows are not excessively large, that the sampler will be able
 * to adjust to the changes in the rate of incoming events.
 *
 * <p>However, there might so rapid changes in incoming events rate that we will optimistically use
 * all allowed samples well before the current window has elapsed or, on the other end of the
 * spectrum, there will be to few incoming events and the sampler will not be able to generate the
 * target number of samples.
 *
 * <p>To smooth out these hiccups the sampler maintains an under-sampling budget which can be used
 * to compensate for too rapid changes in the incoming events rate and maintain the target average
 * number of samples per window.
 */
public class AdaptiveSampler implements Sampler {

  private static final class Counts {
    private final LongAdder testCount = new LongAdder();
    private static final AtomicLongFieldUpdater<Counts> SAMPLE_COUNT =
        AtomicLongFieldUpdater.newUpdater(Counts.class, "sampleCount");
    private volatile long sampleCount = 0L;

    void addTest() {
      testCount.increment();
    }

    boolean addSample(final long limit) {
      return SAMPLE_COUNT.getAndAccumulate(this, limit, (prev, lim) -> Math.min(prev + 1, lim))
          < limit;
    }

    void addSample() {
      SAMPLE_COUNT.incrementAndGet(this);
    }

    void reset() {
      testCount.reset();
      SAMPLE_COUNT.set(this, 0);
    }

    long sampleCount() {
      return SAMPLE_COUNT.get(this);
    }

    long testCount() {
      return testCount.sum();
    }
  }

  @FunctionalInterface
  public interface ConfigListener {
    void onWindowRoll(
        long totalCount, long sampledCount, long budget, double totalAverage, double probability);
  }

  /*
   * Exponential Moving Average (EMA) last element weight.
   * Check out papers about using EMA for streaming data - eg.
   * https://nestedsoftware.com/2018/04/04/exponential-moving-average-on-streaming-data-4hhl.24876.html
   *
   * Corresponds to 'lookback' of N values:
   * With T being the index of the most recent value the lookback of N values means that for all values with index
   * T-K, where K > N, the relative weight of that value computed as (1 - alpha)^K is less or equal than the
   * weight assigned by a plain arithmetic average (= 1/N).
   */
  private final double emaAlpha;
  private final int samplesPerWindow;

  private final AtomicReference<Counts> countsRef;

  // these attributes need to be volatile since they are accessed from user threds as well as the
  // maintenance one
  private volatile double probability = 1d;
  private volatile long samplesBudget;

  // these attributes are accessed solely from the window maintenance thread
  private double totalCountRunningAverage = 0d;
  private double avgSamples;

  private final int budgetLookback;
  private final double budgetAlpha;

  // accessed exclusively from the window maintenance task - does not require any synchronization
  private int countsSlotIdx = 0;
  private final Counts[] countsSlots = new Counts[] {new Counts(), new Counts()};

  private final ConfigListener listener;

  private final Duration windowDuration;
  private final AgentTaskScheduler taskScheduler;

  /**
   * Create a new sampler instance
   *
   * @param windowDuration the sampling window duration
   * @param samplesPerWindow the maximum number of samples in the sampling window
   * @param averageLookback the number of windows to consider in averaging the sampling rate
   * @param budgetLookback the number of windows to consider when computing the sampling budget
   * @param listener an optional listener receiving the sampler config changes
   * @param taskScheduler agent task scheduler to use for periodic rolls
   */
  protected AdaptiveSampler(
      final Duration windowDuration,
      final int samplesPerWindow,
      final int averageLookback,
      final int budgetLookback,
      final @Nullable ConfigListener listener,
      final AgentTaskScheduler taskScheduler,
      boolean startSampler) {

    if (averageLookback < 1) {
      throw new IllegalArgumentException("'averageLookback' argument must be at least 1");
    }
    if (budgetLookback < 1) {
      throw new IllegalArgumentException("'budgetLookback' argument must be at least 1");
    }
    this.samplesPerWindow = samplesPerWindow;
    this.budgetLookback = budgetLookback;
    samplesBudget = samplesPerWindow + (long) budgetLookback * samplesPerWindow;
    emaAlpha = computeIntervalAlpha(averageLookback);
    budgetAlpha = computeIntervalAlpha(budgetLookback);
    countsRef = new AtomicReference<>(countsSlots[0]);
    this.listener = listener;
    if (listener != null) {
      listener.onWindowRoll(0, 0, samplesBudget, totalCountRunningAverage, probability);
    }

    this.windowDuration = windowDuration;
    this.taskScheduler = taskScheduler;

    if (startSampler) {
      start();
    }
  }

  /**
   * Create a new sampler instance with automatic window roll.
   *
   * @param windowDuration the sampling window duration
   * @param samplesPerWindow the maximum number of samples in the sampling window
   * @param averageLookback the number of windows to consider in averaging the sampling rate
   * @param budgetLookback the number of windows to consider when computing the sampling budget
   */
  public AdaptiveSampler(
      final Duration windowDuration,
      final int samplesPerWindow,
      final int averageLookback,
      final int budgetLookback,
      boolean startSampler) {
    this(
        windowDuration,
        samplesPerWindow,
        averageLookback,
        budgetLookback,
        null,
        AgentTaskScheduler.get(),
        startSampler);
  }

  /**
   * Create a new sampler instance with automatic window roll. The instance is automatically
   * started.
   *
   * @param windowDuration the sampling window duration
   * @param samplesPerWindow the maximum number of samples in the sampling window
   * @param averageLookback the number of windows to consider in averaging the sampling rate
   * @param budgetLookback the number of windows to consider when computing the sampling budget
   * @param listener an optional listener receiving the sampler config changes
   */
  public AdaptiveSampler(
      final Duration windowDuration,
      final int samplesPerWindow,
      final int averageLookback,
      final int budgetLookback,
      final ConfigListener listener) {
    this(
        windowDuration,
        samplesPerWindow,
        averageLookback,
        budgetLookback,
        listener,
        AgentTaskScheduler.get(),
        true);
  }

  public void start() {
    taskScheduler.weakScheduleAtFixedRate(
        RollWindowTask.INSTANCE,
        this,
        windowDuration.toNanos(),
        windowDuration.toNanos(),
        TimeUnit.NANOSECONDS);
  }

  @Override
  public boolean sample() {
    final Counts counts = countsRef.get();
    counts.addTest();
    if (ThreadLocalRandom.current().nextDouble() < probability) {
      return counts.addSample(samplesBudget);
    }

    return false;
  }

  @Override
  public boolean keep() {
    final AdaptiveSampler.Counts counts = countsRef.get();
    counts.addTest();
    counts.addSample();
    return true;
  }

  @Override
  public boolean drop() {
    final AdaptiveSampler.Counts counts = countsRef.get();
    counts.addTest();
    return false;
  }

  private void rollWindow() {

    final Counts counts = countsSlots[countsSlotIdx];
    try {
      /*
       * Semi-atomically replace the Counts instance such that sample requests during window maintenance will be
       * using the newly created counts instead of the ones currently processed by the maintenance routine.
       * We are ok with slightly racy outcome where totaCount and sampledCount may not be totally in sync
       * because it allows to avoid contention in the hot-path and the effect on the overall sample rate is minimal
       * and will get compensated in the long run.
       * Theoretically, a compensating system might be devised but it will always require introducing a single point
       * of contention and add a fair amount of complexity. Considering that we are ok with keeping the target sampling
       * rate within certain error margins and this data race is not breaking the margin it is better to keep the
       * code simple and reasonably fast.
       */
      countsSlotIdx = (countsSlotIdx++) % 2;
      countsRef.set(countsSlots[countsSlotIdx]);
      final long totalCount = counts.testCount();
      final long sampledCount = counts.sampleCount();

      samplesBudget = calculateBudgetEma(sampledCount);

      if (totalCountRunningAverage == 0 || emaAlpha <= 0.0d) {
        totalCountRunningAverage = totalCount;
      } else {
        totalCountRunningAverage =
            totalCountRunningAverage + emaAlpha * (totalCount - totalCountRunningAverage);
      }

      if (totalCountRunningAverage <= 0) {
        probability = 1;
      } else {
        probability = Math.min(samplesBudget / totalCountRunningAverage, 1d);
      }
      if (listener != null) {
        listener.onWindowRoll(
            totalCount, sampledCount, samplesBudget, totalCountRunningAverage, probability);
      }
    } finally {
      // Reset the previous counts slot
      counts.reset();
    }
  }

  private long calculateBudgetEma(final long sampledCount) {
    avgSamples =
        Double.isNaN(avgSamples) || budgetAlpha <= 0.0d
            ? sampledCount
            : avgSamples + budgetAlpha * (sampledCount - avgSamples);
    return Math.round(Math.max(samplesPerWindow - avgSamples, 0) * budgetLookback);
  }

  private static double computeIntervalAlpha(final int lookback) {
    return 1 - Math.pow(lookback, -1d / lookback);
  }

  private static class RollWindowTask implements Task<AdaptiveSampler> {

    static final RollWindowTask INSTANCE = new RollWindowTask();

    @Override
    public void run(final AdaptiveSampler target) {
      target.rollWindow();
    }
  }

  // access for tests
  long testCount() {
    return countsRef.get().testCount();
  }

  long sampleCount() {
    return countsRef.get().sampleCount();
  }
}
