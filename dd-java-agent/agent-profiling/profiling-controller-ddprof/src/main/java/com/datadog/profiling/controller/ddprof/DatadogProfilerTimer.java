package com.datadog.profiling.controller.ddprof;

import com.datadog.profiling.ddprof.DatadogProfiler;
import com.datadog.profiling.ddprof.QueueTimeTracker;
import datadog.trace.api.profiling.Timer;
import datadog.trace.api.profiling.Timing;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;

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
    long localRootSpanId = 0;
    long spanId = 0;
    AgentSpan activeSpan = AgentTracer.activeSpan();
    if (activeSpan != null) {
      spanId = activeSpan.getSpanId();
      AgentSpan rootSpan = activeSpan.getLocalRootSpan();
      localRootSpanId = rootSpan == null ? spanId : rootSpan.getSpanId();
    }
    if (type == TimerType.QUEUEING) {
      QueueTimeTracker tracker = profiler.newQueueTimeTracker(localRootSpanId, spanId);
      if (tracker != null) {
        return tracker;
      }
    }
    return Timing.NoOp.INSTANCE;
  }
}
