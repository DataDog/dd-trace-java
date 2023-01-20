package com.datadog.profiling.ddprof;

import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;

/**
 * This class must be installed early to be able to see all scope initialisations, which means it
 * must not be modified to depend on JFR, so that it can be installed before tracing starts.
 */
public class DatadogProfilingIntegration implements ProfilingContextIntegration {

  private static final DatadogProfiler DDPROF = DatadogProfiler.getInstance();
  private static final boolean WALLCLOCK_ENABLED =
      DatadogProfilerConfig.isWallClockProfilerEnabled();

  @Override
  public void onAttach() {
    if (WALLCLOCK_ENABLED) {
      DDPROF.addThread();
    }
  }

  @Override
  public void onDetach() {
    if (WALLCLOCK_ENABLED) {
      DDPROF.removeThread();
    }
  }

  @Override
  public void setContext(long rootSpanId, long spanId) {
    DDPROF.setContext(spanId, rootSpanId);
  }

  @Override
  public void setContextValue(String attribute, String value) {
    DDPROF.setContextValue(attribute, value);
  }
}
