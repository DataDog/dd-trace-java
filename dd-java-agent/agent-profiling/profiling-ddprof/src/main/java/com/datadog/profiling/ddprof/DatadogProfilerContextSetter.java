package com.datadog.profiling.ddprof;

import datadog.trace.api.experimental.ProfilingContextSetter;

public class DatadogProfilerContextSetter implements ProfilingContextSetter {

  private final int offset;
  private final DatadogProfiler profiler;

  public DatadogProfilerContextSetter(String attribute, DatadogProfiler profiler) {
    this.offset = profiler.offsetOf(attribute);
    this.profiler = profiler;
  }

  @Override
  public void set(CharSequence value) {
    profiler.setContextValue(offset, value);
  }

  @Override
  public void clear() {
    profiler.clearContextValue(offset);
  }
}
