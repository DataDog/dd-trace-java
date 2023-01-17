package com.datadog.profiling.ddprof;

import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;

/**
 * This class must be installed early to be able to see all scope initialisations, which means it
 * must not be modified to depend on JFR, so that it can be installed before tracing starts.
 */
public class ContextThreadFilter implements ProfilingContextIntegration {

  private static final DatadogProfiler DDPROF = DatadogProfiler.getInstance();
  private static final boolean WALLCLOCK_ENABLED =
      DatadogProfilerConfig.isWallClockProfilerEnabled();

  @Override
  public void onAttach(int tid) {
    if (WALLCLOCK_ENABLED) {
      DDPROF.addThread(tid);
    }
  }

  @Override
  public void onDetach(int tid) {
    if (WALLCLOCK_ENABLED) {
      DDPROF.removeThread(tid);
    }
  }

  @Override
  public void setContext(int tid, long rootSpanId, long spanId) {
    DDPROF.setContext(tid, spanId, rootSpanId);
  }

  @Override
  public int getNativeThreadId() {
    return DDPROF.getNativeThreadId();
  }
}
