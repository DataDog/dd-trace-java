package com.datadog.profiling.controller.ddprof;

import static datadog.trace.api.config.ProfilingConfig.PROFILING_QUEUEING_TIME_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_QUEUEING_TIME_ENABLED_DEFAULT;

import com.datadog.profiling.ddprof.DatadogProfiler;
import com.datadog.profiling.ddprof.QueueTimeTracker;
import datadog.trace.api.profiling.Timer;
import datadog.trace.api.profiling.Timing;
import datadog.trace.bootstrap.config.provider.ConfigProvider;

public class DatadogProfilerTimer implements Timer {

  // don't use Config because it may use ThreadPoolExecutor to initialize itself
  private static final boolean IS_PROFILING_QUEUEING_TIME_ENABLED =
      ConfigProvider.getInstance()
          .getBoolean(PROFILING_QUEUEING_TIME_ENABLED, PROFILING_QUEUEING_TIME_ENABLED_DEFAULT);

  private final DatadogProfiler profiler;

  public DatadogProfilerTimer(DatadogProfiler profiler) {
    this.profiler = profiler;
  }

  public DatadogProfilerTimer() {
    this(DatadogProfiler.getInstance());
  }

  @Override
  public Timing start(TimerType type) {
    if (IS_PROFILING_QUEUEING_TIME_ENABLED && type == TimerType.QUEUEING) {
      QueueTimeTracker tracker = profiler.newQueueTimeTracker();
      if (tracker != null) {
        return tracker;
      }
    }
    return Timing.NoOp.INSTANCE;
  }
}
