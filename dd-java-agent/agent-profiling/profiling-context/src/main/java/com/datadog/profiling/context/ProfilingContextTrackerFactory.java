package com.datadog.profiling.context;

import datadog.trace.api.profiling.ContextTracker;
import datadog.trace.api.profiling.ContextTrackerFactory;

public final class ProfilingContextTrackerFactory implements ContextTrackerFactory.Implementation {
  private static final ProfilingContextTrackerFactory INSTANCE = new ProfilingContextTrackerFactory();

  public static void register() {
    ContextTrackerFactory.registerImplementation(INSTANCE);
  }

  @Override
  public ContextTracker instance() {
    return new ProfilingContextTracker();
  }
}
