package datadog.trace.api.profiling;

import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_PRECHECK;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_PRECHECK_DEFAULT;

import datadog.trace.bootstrap.config.provider.ConfigProvider;

/** Shared configuration gates for Java-level {@code datadog.TaskBlock} instrumentations. */
public final class TaskBlockInstrumentationConfig {
  private TaskBlockInstrumentationConfig() {}

  public static boolean isWallPrecheckEnabled(final ConfigProvider configProvider) {
    return configProvider.getBoolean(
        PROFILING_DATADOG_PROFILER_WALL_PRECHECK, PROFILING_DATADOG_PROFILER_WALL_PRECHECK_DEFAULT);
  }
}
