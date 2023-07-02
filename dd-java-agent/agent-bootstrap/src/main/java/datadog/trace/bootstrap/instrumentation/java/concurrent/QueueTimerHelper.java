package datadog.trace.bootstrap.instrumentation.java.concurrent;

import static datadog.trace.api.config.ProfilingConfig.PROFILING_QUEUEING_TIME_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_QUEUEING_TIME_ENABLED_DEFAULT;
import static datadog.trace.bootstrap.TaskWrapper.getUnwrappedType;

import datadog.trace.api.profiling.QueueTiming;
import datadog.trace.api.profiling.Timer;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.jfr.InstrumentationBasedProfiling;

public class QueueTimerHelper {

  // don't use Config because it may use ThreadPoolExecutor to initialize itself
  private static final boolean IS_PROFILING_QUEUEING_TIME_ENABLED =
      ConfigProvider.getInstance()
          .getBoolean(PROFILING_QUEUEING_TIME_ENABLED, PROFILING_QUEUEING_TIME_ENABLED_DEFAULT);

  public static <T> void startQueuingTimer(
      ContextStore<T, State> taskContextStore, Class<?> schedulerClass, T task) {
    if (IS_PROFILING_QUEUEING_TIME_ENABLED && InstrumentationBasedProfiling.isJFRReady()) {
      State state = taskContextStore.get(task);
      if (task != null && state != null) {
        QueueTiming timing =
            (QueueTiming) AgentTracer.get().getTimer().start(Timer.TimerType.QUEUEING);
        timing.setTask(getUnwrappedType(task));
        timing.setScheduler(schedulerClass);
        state.setTiming(timing);
      }
    }
  }
}
