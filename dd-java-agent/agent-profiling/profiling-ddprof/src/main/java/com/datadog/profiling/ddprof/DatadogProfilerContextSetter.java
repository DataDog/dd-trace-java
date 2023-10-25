package com.datadog.profiling.ddprof;

public class DatadogProfilerContextSetter
    implements datadog.trace.api.profiling.ProfilingContextAttribute {

  private final int offset;
  private final DatadogProfiler profiler;

  public DatadogProfilerContextSetter(String attribute, DatadogProfiler profiler) {
    this.offset = profiler.offsetOf(attribute);
    this.profiler = profiler;
  }

  public void set(CharSequence value) {
    profiler.setContextValue(offset, value);
  }

  public void clear() {
    profiler.clearContextValue(offset);
  }
}
