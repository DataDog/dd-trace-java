// Copyright 2026 Datadog, Inc.
package datadog.trace.api.profiling;

import static datadog.environment.JavaVirtualMachine.isJ9;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_CONTEXT_FILTER;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_CONTEXT_FILTER_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_PRECHECK;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_PRECHECK_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ULTRA_MINIMAL;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_ENABLED;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.config.provider.ConfigProvider;

/** Shared configuration gates for Java-level {@code datadog.TaskBlock} instrumentations. */
public final class TaskBlockInstrumentationConfig {
  private TaskBlockInstrumentationConfig() {}

  public static boolean isWallPrecheckEnabled(final ConfigProvider configProvider) {
    return getDdprofBoolean(
        configProvider,
        PROFILING_DATADOG_PROFILER_WALL_PRECHECK,
        PROFILING_DATADOG_PROFILER_WALL_PRECHECK_DEFAULT);
  }

  /** Returns whether the effective wall-clock configuration samples all threads. */
  public static boolean isAllThreadWallScope(final ConfigProvider configProvider) {
    boolean tracingEnabled = configProvider.getBoolean(TRACE_ENABLED, true);
    return !tracingEnabled
        || !getDdprofBoolean(
            configProvider,
            PROFILING_DATADOG_PROFILER_WALL_CONTEXT_FILTER,
            PROFILING_DATADOG_PROFILER_WALL_CONTEXT_FILTER_DEFAULT);
  }

  /**
   * Common enablement gate shared by TaskBlock instrumentations.
   *
   * <p>The producer is useful only when ddprof and its effective wall-clock engine are enabled,
   * wall precheck is requested, and the wall profiler samples all threads. In context-only scope,
   * the existing context filter already excludes out-of-context threads.
   */
  public static boolean isEnabled(final Config config, final ConfigProvider configProvider) {
    return config.isDatadogProfilerEnabled()
        && isWallClockProfilerEnabled(configProvider)
        && isWallPrecheckEnabled(configProvider)
        && isAllThreadWallScope(configProvider);
  }

  private static boolean isWallClockProfilerEnabled(final ConfigProvider configProvider) {
    boolean ultraMinimal = configProvider.getBoolean(PROFILING_ULTRA_MINIMAL, false);
    boolean tracingEnabled = configProvider.getBoolean(TRACE_ENABLED, true);
    boolean enabledByDefault = !ultraMinimal && tracingEnabled && !isJ9();
    return getDdprofBoolean(
        configProvider, PROFILING_DATADOG_PROFILER_WALL_ENABLED, enabledByDefault);
  }

  private static boolean getDdprofBoolean(
      ConfigProvider configProvider, String key, boolean defaultValue) {
    return configProvider.getBoolean(
        key, configProvider.getBoolean(key.replace(".ddprof.", ".async."), defaultValue));
  }
}
