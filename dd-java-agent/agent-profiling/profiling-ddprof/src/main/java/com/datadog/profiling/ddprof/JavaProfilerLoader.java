package com.datadog.profiling.ddprof;

import com.datadoghq.profiler.JavaProfiler;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.util.TempLocationManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

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
      String scratch = configProvider.getString(ProfilingConfig.PROFILING_DATADOG_PROFILER_SCRATCH);
      if (scratch == null) {
        Path scratchPath = TempLocationManager.getInstance().getTempDir().resolve("scratch");
        if (!Files.exists(scratchPath)) {
          Files.createDirectories(
              scratchPath,
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")));
        }
        scratch = scratchPath.toString();
      }
      profiler =
          JavaProfiler.getInstance(
              configProvider.getString(ProfilingConfig.PROFILING_DATADOG_PROFILER_LIBPATH),
              scratch);
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
