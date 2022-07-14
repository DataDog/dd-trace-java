package com.datadog.profiling.context;

import com.datadog.profiling.context.allocator.Allocators;
import datadog.trace.api.StatsDClient;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.api.profiling.TracingContextTracker;
import datadog.trace.api.profiling.TracingContextTrackerFactory;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.util.AgentTaskScheduler;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AsyncProfilerTracingContextTrackerFactory
    implements TracingContextTrackerFactory.Implementation {
  private static final Logger log =
      LoggerFactory.getLogger(AsyncProfilerTracingContextTrackerFactory.class);

  public static void register(ConfigProvider configProvider) {
    if (configProvider.getBoolean(
        ProfilingConfig.PROFILING_ASYNC_TRACING_CONTEXT_ENABLED,
        ProfilingConfig.PROFILING_ASYNC_TRACING_CONTEXT_ENABLED_DEFAULT)) {
      TracingContextTrackerFactory.registerImplementation(
          new AsyncProfilerTracingContextTrackerFactory());
    }
  }

  @Override
  public TracingContextTracker instance(AgentSpan span) {
    AsyncProfilerTracingContextTracker instance =
        new AsyncProfilerTracingContextTracker(span);
    return instance;
  }
}
