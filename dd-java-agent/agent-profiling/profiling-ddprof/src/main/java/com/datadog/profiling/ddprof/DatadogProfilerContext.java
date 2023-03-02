package com.datadog.profiling.ddprof;

import static com.datadog.profiling.ddprof.DatadogProfilingIntegration.DDPROF;

import datadog.trace.bootstrap.instrumentation.api.ContinuableContext;

public class DatadogProfilerContext implements ContinuableContext {

  final int[] tags;

  public DatadogProfilerContext(int[] tags) {
    this.tags = tags;
  }

  @Override
  public void activate() {
    for (int i = 0; i < tags.length; i++) {
      DDPROF.setContextValue(i, tags[i]);
    }
  }

  @Override
  public void deactivate() {
    for (int i = 0; i < tags.length; i++) {
      DDPROF.clearContextValue(i);
    }
  }
}
