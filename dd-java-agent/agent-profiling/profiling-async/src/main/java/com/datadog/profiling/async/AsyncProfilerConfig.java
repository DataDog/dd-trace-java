package com.datadog.profiling.async;

import static datadog.trace.api.config.ProfilingConfig.PROFILING_ASYNC_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ASYNC_ENABLED_DEFAULT;

import datadog.trace.bootstrap.config.provider.ConfigProvider;

public class AsyncProfilerConfig {

  private static final boolean ASYNC_PROFILER_ENABLED =
      ConfigProvider.getInstance()
          .getBoolean(PROFILING_ASYNC_ENABLED, PROFILING_ASYNC_ENABLED_DEFAULT);

  public static boolean isAsyncProfilerEnabled() {
    return ASYNC_PROFILER_ENABLED;
  }
}
