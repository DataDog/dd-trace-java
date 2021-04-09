package datadog.trace.api.sampling;

import datadog.trace.util.AgentTaskScheduler;
import datadog.trace.util.AgentTaskScheduler.Task;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

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
 * to adjust to the changes in the rate of incoming events. The desired sampling rate is
 * recalculated after each window using PID controller
 * (https://en.wikipedia.org/wiki/PID_controller) thanks to which the sampler is able to accommodate
 * changing rate of the incoming events rather smoothly. For a typical event distribution the error
 * margin will be less than 5% but in a pathological case of highly bursty event stream with
 * interval of almost no activity being followed by a window with extremely large amount of events
 * the error margin may be up to 50% of the expected sample rate when measured over 100+ windows.
 */
public final class AdaptiveSampler {

  private static final class Window {
    private final LongAdder testCounter = new LongAdder();
    private final AtomicLong sampleCounter = new AtomicLong(0L);

    void addTest() {
      testCounter.increment();
    }

    boolean addSample(final long limit) {
      return sampleCounter.getAndUpdate(s -> s + (s < limit ? 1 : 0)) < limit;
    }

    void reset() {
      testCounter.reset();
      sampleCounter.set(0);
    }
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

  private final AtomicReference<Window> windowRef;

  // these attributes need to be volatile since they are accessed from user threads as well as the
  // maintenance one
  private volatile double probability = 1d;
  private volatile long samplesBudget;

  // these attributes are accessed solely from the window maintenance thread
  private double totalCountRunningAverage = 0d;
  private double avgSamples;

  // accessed exclusively from the window maintenance task - does not require any synchronization
  private int countsSlotIdx = 0;
  private final Window[] windowSlots = new Window[] {new Window(), new Window()};

  /*
   * The target sample number for the next window is controlled by a simple PID controller.
   * See https://en.wikipedia.org/wiki/PID_controller for more details.
   * The following fields are the integration, derivation and proportional coefficients, respectively.
   * The values were derived based on the AdaptiveSamplerTest repeatable runs such that the error margins
   * are acceptable.
   */
  private static final double KI = 0.02d;
  private static final double KD = 0.8d;
  private static final double KP = 0.8d;

  /**
   * Create a new sampler instance
   *
   * @param windowDuration the sampling window duration
   * @param samplesPerWindow the maximum number of samples in the sampling window
   * @param lookback the number of windows to consider in averaging the sampling rate
   * @param taskScheduler agent task scheduler to use for periodic rolls
   */
  public AdaptiveSampler(
      final Duration windowDuration,
      final int samplesPerWindow,
      final int lookback,
      final AgentTaskScheduler taskScheduler) {

    this.samplesPerWindow = samplesPerWindow;
    samplesBudget = samplesPerWindow;
    emaAlpha = computeIntervalAlpha(lookback);
    windowRef = new AtomicReference<>(windowSlots[0]);

    taskScheduler.weakScheduleAtFixedRate(
        RollWindowTask.INSTANCE,
        this,
        windowDuration.toNanos(),
        windowDuration.toNanos(),
        TimeUnit.NANOSECONDS);
  }

  /**
   * Create a new sampler instance with automatic window roll.
   *
   * @param windowDuration the sampling window duration
   * @param samplesPerWindow the maximum number of samples in the sampling window
   * @param lookback the number of windows to consider in averaging the sampling rate
   */
  public AdaptiveSampler(
      final Duration windowDuration, final int samplesPerWindow, final int lookback) {
    this(windowDuration, samplesPerWindow, lookback, AgentTaskScheduler.INSTANCE);
  }

  /**
   * Provides binary answer whether the current event is to be sampled
   *
   * @return {@literal true} if the event should be sampled
   */
  public final boolean sample() {
    final Window window = windowRef.get();
    window.addTest();
    if (ThreadLocalRandom.current().nextDouble() < probability) {
      return window.addSample(samplesBudget);
    }

    return false;
  }

  private long errorSum = 0;

  private void rollWindow() {

    final Window previousWindow = windowSlots[countsSlotIdx];
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
      windowRef.set(windowSlots[countsSlotIdx]);
      final long totalCount = previousWindow.testCounter.sum();
      final long sampledCount = previousWindow.sampleCounter.get();

      long diff = samplesPerWindow - sampledCount;
      errorSum += diff;
      double proportional = (double) diff / samplesPerWindow;

      // see https://en.wikipedia.org/wiki/PID_controller#Mathematical_form
      double adjustment = KI * errorSum + KD * diff + KP * proportional;

      // this will never be updated concurrently so we can mutate the volatile long safely
      samplesBudget = (long) (samplesBudget + adjustment);

      if (totalCountRunningAverage == 0) {
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
    } finally {
      // Reset the previous counts slot
      previousWindow.reset();
    }
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
}
