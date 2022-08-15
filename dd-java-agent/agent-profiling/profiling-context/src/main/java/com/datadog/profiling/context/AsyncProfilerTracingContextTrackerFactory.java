package com.datadog.profiling.context;

import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.api.profiling.TracingContextTracker;
import datadog.trace.api.profiling.TracingContextTrackerFactory;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public final class AsyncProfilerTracingContextTrackerFactory
    implements TracingContextTrackerFactory.Implementation {

  public static boolean isEnabled(ConfigProvider configProvider) {
    return configProvider.getBoolean(
            ProfilingConfig.PROFILING_ASYNC_ENABLED,
            ProfilingConfig.PROFILING_ASYNC_ENABLED_DEFAULT)
        && configProvider.getBoolean(
            ProfilingConfig.PROFILING_ASYNC_WALL_ENABLED,
            ProfilingConfig.PROFILING_ASYNC_WALL_ENABLED_DEFAULT)
        && configProvider.getBoolean(
            ProfilingConfig.PROFILING_ASYNC_WALL_FILTER_ON_CONTEXT,
            ProfilingConfig.PROFILING_ASYNC_WALL_FILTER_ON_CONTEXT_DEFAULT);
  }

  public static void register(ConfigProvider configProvider) {
    if (isEnabled(configProvider)) {
      TracingContextTrackerFactory.registerImplementation(
          new AsyncProfilerTracingContextTrackerFactory());
    }
  }

  AsyncProfilerTracingContextTrackerFactory() {}

  @Override
  public TracingContextTracker instance(AgentSpan span) {
    return new AsyncProfilerTracingContextTracker(span);
  }
}
