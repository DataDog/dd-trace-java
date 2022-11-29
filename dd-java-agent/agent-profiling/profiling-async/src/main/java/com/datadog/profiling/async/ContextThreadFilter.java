package com.datadog.profiling.async;

import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;

/**
 * This class must be installed early to be able to see all scope initialisations, which means it
 * must not be modified to depend on JFR, so that it can be installed before tracing starts.
 */
public class ContextThreadFilter implements ProfilingContextIntegration {

  private static final AsyncProfiler ASYNC_PROFILER = AsyncProfiler.getInstance();

  @Override
  public void onAttach() {
    if (AsyncProfilerConfig.isWallClockProfilerEnabled()) {
      ASYNC_PROFILER.addCurrentThread();
    }
  }

  @Override
  public void onDetach() {
    if (AsyncProfilerConfig.isWallClockProfilerEnabled()) {
      ASYNC_PROFILER.removeCurrentThread();
    }
  }

  @Override
  public void setContext(int tid, long rootSpanId, long spanId) {
    ASYNC_PROFILER.setContext(tid, spanId, rootSpanId);
  }

  @Override
  public int getNativeThreadId() {
    return ASYNC_PROFILER.getNativeThreadId();
  }
}
