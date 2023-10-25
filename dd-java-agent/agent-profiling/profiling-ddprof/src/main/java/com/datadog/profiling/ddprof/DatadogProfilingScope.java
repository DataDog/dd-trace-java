package com.datadog.profiling.ddprof;

import datadog.trace.api.profiling.ProfilingContextAttribute;
import datadog.trace.api.profiling.ProfilingScope;

public class DatadogProfilingScope implements ProfilingScope {
  private final DatadogProfiler profiler;
  private final int[] snapshot;

  public DatadogProfilingScope(DatadogProfiler profiler) {
    this.profiler = profiler;
    this.snapshot = profiler.snapshot();
  }

  @Override
  public void setContextValue(String attribute, String value) {
    profiler.setContextValue(attribute, value);
  }

  @Override
  public void setContextValue(ProfilingContextAttribute attribute, String value) {
    if (attribute instanceof DatadogProfilerContextSetter) {
      ((DatadogProfilerContextSetter) attribute).set(value);
    }
  }

  @Override
  public void clearContextValue(String attribute) {
    profiler.clearContextValue(attribute);
  }

  @Override
  public void clearContextValue(ProfilingContextAttribute attribute) {
    if (attribute instanceof DatadogProfilerContextSetter) {
      ((DatadogProfilerContextSetter) attribute).clear();
    }
  }

  @Override
  public void close() {
    for (int i = 0; i < snapshot.length; i++) {
      profiler.setContextValue(i, snapshot[i]);
    }
  }
}
