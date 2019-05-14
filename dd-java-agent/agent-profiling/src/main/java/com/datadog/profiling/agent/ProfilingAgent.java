package com.datadog.profiling.agent;

import com.datadog.profiling.controller.ConfigurationException;
import com.datadog.profiling.controller.ProfilingSystem;
import com.datadog.profiling.controller.UnsupportedEnvironmentException;
import com.datadog.profiling.uploader.ChunkUploader;
import datadog.trace.api.Config;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;

/**
 * Simple agent wrapper for starting the profiling agent from the command-line, without requiring
 * the APM agent. This makes it possible to run the profiling agent stand-alone. Of course, this
 * also means no contextual events from the tracing will be present.
 */
@Slf4j
public class ProfilingAgent {
  private static final String ENV_VAR_TAGS = "DD_PROFILE_TAGS"; // comma separated, no spaces

  // Overkill to make these volatile?
  private static ProfilingSystem profiler;

  public static synchronized void run() throws IllegalArgumentException {
    if (profiler == null) {
      final Config config = Config.get();
      if (config.getProfilingApiKey() == null) {
        log.info("Profiling: no API key, profiling disabled");
        return;
      }

      final ChunkUploader uploader =
          new ChunkUploader(
              config.getProfilingUrl(),
              config.getProfilingApiKey(),
              config.getMergedProfilingTags());
      try {
        profiler =
            new ProfilingSystem(
                uploader.getRecordingDataListener(),
                config.getProfilingPeriodicDelay(),
                config.getProfilingPeriodicPeriod(),
                config.getProfilingPeriodicDuration());
        profiler.start();
        log.warn("Periodic profiling has started!");
      } catch (final UnsupportedEnvironmentException | IOException | ConfigurationException e) {
        log.warn("Failed to initialize profiling agent!", e);
      }
    }
  }
}
