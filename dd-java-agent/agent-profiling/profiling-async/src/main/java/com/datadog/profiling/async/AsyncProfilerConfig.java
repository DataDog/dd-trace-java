package com.datadog.profiling.async;

import static datadog.trace.api.config.ProfilingConfig.PROFILING_ASYNC_LOG_LEVEL;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ASYNC_LOG_LEVEL_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ASYNC_WALL_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ASYNC_WALL_ENABLED_DEFAULT;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.config.provider.ConfigProvider;

public class AsyncProfilerConfig {

  private static final boolean ASYNC_PROFILER_ENABLED = Config.get().isAsyncProfilerEnabled();

  private static final boolean ASYNC_PROFILER_WALLCLOCK_ENABLED =
      ConfigProvider.getInstance()
          .getBoolean(PROFILING_ASYNC_WALL_ENABLED, PROFILING_ASYNC_WALL_ENABLED_DEFAULT);

  public static boolean isWallClockProfilerEnabled() {
    return ASYNC_PROFILER_ENABLED && ASYNC_PROFILER_WALLCLOCK_ENABLED;
  }

  public static String getLogLevel() {
    return ConfigProvider.getInstance()
        .getString(PROFILING_ASYNC_LOG_LEVEL, PROFILING_ASYNC_LOG_LEVEL_DEFAULT);
  }
}
