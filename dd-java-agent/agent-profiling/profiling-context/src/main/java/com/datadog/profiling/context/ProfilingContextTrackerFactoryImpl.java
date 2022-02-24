package com.datadog.profiling.context;

import com.datadog.profiling.context.allocator.Allocators;
import datadog.trace.api.GlobalTracer;
import datadog.trace.api.profiling.ProfilingContextTracker;
import datadog.trace.api.profiling.ProfilingContextTrackerFactory;

public final class ProfilingContextTrackerFactoryImpl
    implements ProfilingContextTrackerFactory.Implementation {
  private static final ProfilingContextTrackerFactoryImpl INSTANCE =
      new ProfilingContextTrackerFactoryImpl();

  public static void register() {
    ProfilingContextTrackerFactory.registerImplementation(INSTANCE);
  }

  private final Allocator allocator = Allocators.heapAllocator(8 * 1024 * 1024, 32);

  private ProfilingContextTrackerFactoryImpl() {
    GlobalTracer.get().addTraceInterceptor(new ProfilingContextSettingInterceptor());
  }

  @Override
  public ProfilingContextTracker instance() {
    return new ProfilingContextTrackerImpl(allocator);
  }
}
