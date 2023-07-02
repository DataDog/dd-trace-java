package com.datadog.profiling.controller.ddprof;

import com.datadog.profiling.ddprof.DatadogProfiler;
import com.datadog.profiling.ddprof.QueueTimeTracker;
import datadog.trace.api.profiling.Timer;
import datadog.trace.api.profiling.Timing;

public class DatadogProfilerTimer implements Timer {

  private final DatadogProfiler profiler;

  public DatadogProfilerTimer(DatadogProfiler profiler) {
    this.profiler = profiler;
  }

  public DatadogProfilerTimer() {
    this(DatadogProfiler.getInstance());
  }

  @Override
  public Timing start(TimerType type) {
    if (type == TimerType.QUEUEING) {
      QueueTimeTracker tracker = profiler.newQueueTimeTracker();
      if (tracker != null) {
        return tracker;
      }
    }
    return Timing.NoOp.INSTANCE;
  }
}
