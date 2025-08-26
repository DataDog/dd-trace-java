package datadog.trace.bootstrap.instrumentation.java.concurrent;

import datadog.config.ConfigProvider;
import datadog.trace.api.Platform;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.api.profiling.QueueTiming;
import datadog.trace.api.profiling.Timer;
import datadog.trace.api.profiling.Timing;
import datadog.trace.api.sampling.PerRecordingRateLimiter;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.jfr.InstrumentationBasedProfiling;
import java.time.Duration;
import java.time.temporal.ChronoUnit;

public class QueueTimerHelper {

  private static final class RateLimiterHolder {
    // indirection to prevent needing to instantiate the class and its transitive dependencies
    // in graal native image
    private static final PerRecordingRateLimiter RATE_LIMITER =
        new PerRecordingRateLimiter(
            Duration.of(500, ChronoUnit.MILLIS),
            10_000, // hard limit on queue events
            Duration.ofSeconds(
                ConfigProvider.getInstance()
                    .getInteger(
                        ProfilingConfig.PROFILING_UPLOAD_PERIOD,
                        ProfilingConfig.PROFILING_UPLOAD_PERIOD_DEFAULT)));
  }

  public static <T> void startQueuingTimer(
      ContextStore<T, State> taskContextStore,
      Class<?> schedulerClass,
      Class<?> queueClass,
      int queueLength,
      T task) {
    State state = taskContextStore.get(task);
    startQueuingTimer(state, schedulerClass, queueClass, queueLength, task);
  }

  public static void startQueuingTimer(
      State state, Class<?> schedulerClass, Class<?> queueClass, int queueLength, Object task) {
    if (Platform.isNativeImage()) {
      // explicitly not supported for Graal native image
      return;
    }
    // TODO consider queue length based sampling here to reduce overhead
    // avoid calling this before JFR is initialised because it will lead to reading the wrong
    // TSC frequency before JFR has set it up properly
    if (task != null && state != null && InstrumentationBasedProfiling.isJFRReady()) {
      QueueTiming timing =
          (QueueTiming) AgentTracer.get().getProfilingContext().start(Timer.TimerType.QUEUEING);
      timing.setTask(task);
      timing.setScheduler(schedulerClass);
      timing.setQueue(queueClass);
      timing.setQueueLength(queueLength);
      state.setTiming(timing);
    }
  }

  public static void stopQueuingTimer(Timing timing) {
    if (Platform.isNativeImage()) {
      // explicitly not supported for Graal native image
      return;
    }
    if (timing != null && timing.sample() && RateLimiterHolder.RATE_LIMITER.permit()) {
      timing.report();
    }
  }
}
