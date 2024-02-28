package com.datadog.profiling.ddprof;

import com.datadoghq.profiler.JavaProfiler;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;

/**
 * Only loading the profiler itself needs to be protected as a singleton. Separating the loading
 * logic minimises the amount of config which needs to be early loaded to just the library location.
 */
public class JavaProfilerLoader {
  static final JavaProfiler PROFILER;
  public static final Throwable REASON_NOT_LOADED;

  static {
    JavaProfiler profiler;
    Throwable reasonNotLoaded = null;
    try {
      ConfigProvider configProvider = ConfigProvider.getInstance();
      profiler =
          JavaProfiler.getInstance(
              configProvider.getString(ProfilingConfig.PROFILING_DATADOG_PROFILER_LIBPATH),
              configProvider.getString(
                  ProfilingConfig.PROFILING_DATADOG_PROFILER_SCRATCH,
                  ProfilingConfig.PROFILING_DATADOG_PROFILER_SCRATCH_DEFAULT));
      // sanity test - force load Datadog profiler to catch it not being available early
      profiler.execute("status");
    } catch (Throwable t) {
      reasonNotLoaded = t;
      profiler = null;
    }
    PROFILER = profiler;
    REASON_NOT_LOADED = reasonNotLoaded;
  }
}
