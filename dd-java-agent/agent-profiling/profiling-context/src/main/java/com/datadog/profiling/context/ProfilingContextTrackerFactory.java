package com.datadog.profiling.context;

import com.datadog.profiling.context.allocator.Allocators;
import datadog.trace.api.profiling.ContextTracker;
import datadog.trace.api.profiling.ContextTrackerFactory;

public final class ProfilingContextTrackerFactory implements ContextTrackerFactory.Implementation {
  private static final ProfilingContextTrackerFactory INSTANCE = new ProfilingContextTrackerFactory();

  public static void register() {
    ContextTrackerFactory.registerImplementation(INSTANCE);
  }

  private final Allocator allocator = Allocators.heapAllocator(8 * 1024 * 1024, 32);

  @Override
  public ContextTracker instance() {
    return new ProfilingContextTracker(allocator);
  }
}
