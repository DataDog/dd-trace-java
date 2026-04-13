package datadog.trace.instrumentation.directbytebuffer;

import static datadog.trace.api.config.ProfilingConfig.PROFILING_DIRECT_ALLOCATION_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DIRECT_MEMORY_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DIRECT_MEMORY_ENABLED_DEFAULT;

import datadog.trace.bootstrap.config.provider.ConfigProvider;

final class DirectMemoryProfilingHelper {

  static boolean isEnabled(ConfigProvider cp) {
    return cp.getBoolean(
        PROFILING_DIRECT_MEMORY_ENABLED,
        cp.getBoolean(
            PROFILING_DIRECT_ALLOCATION_ENABLED, PROFILING_DIRECT_MEMORY_ENABLED_DEFAULT));
  }

  private DirectMemoryProfilingHelper() {}
}
