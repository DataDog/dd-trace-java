package com.datadog.profiling.async;

import static datadog.trace.api.config.ProfilingConfig.*;

import datadog.trace.bootstrap.config.provider.ConfigProvider;

public class AsyncProfilerConfig {

  private static final boolean ASYNC_PROFILER_ENABLED =
      ConfigProvider.getInstance()
          .getBoolean(PROFILING_ASYNC_ENABLED, PROFILING_ASYNC_ENABLED_DEFAULT);

  private static final boolean ASYNC_PROFILER_THREAD_FILTER_ENABLED =
      ConfigProvider.getInstance()
          .getBoolean(
              PROFILING_ASYNC_WALL_THREAD_FILTER_ENABLED,
              PROFILING_ASYNC_WALL_THREAD_FILTER_ENABLED_DEFAULT);

  public static boolean isWallThreadFilterEnabled() {
    return ASYNC_PROFILER_ENABLED && ASYNC_PROFILER_THREAD_FILTER_ENABLED;
  }

  public static String getLogLevel() {
    return ConfigProvider.getInstance()
        .getString(PROFILING_ASYNC_LOG_LEVEL, PROFILING_ASYNC_LOG_LEVEL_DEFAULT);
  }
}
