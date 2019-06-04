package com.datadog.profiling.agent;

import com.datadog.profiling.controller.ConfigurationException;
import com.datadog.profiling.controller.Controller;
import com.datadog.profiling.controller.ControllerFactory;
import com.datadog.profiling.controller.ProfilingSystem;
import com.datadog.profiling.controller.UnsupportedEnvironmentException;
import com.datadog.profiling.uploader.RecordingUploader;
import datadog.trace.api.Config;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;

/** Profiling agent implementation */
@Slf4j
public class ProfilingAgent {

  private static volatile ProfilingSystem PROFILER;

  public static synchronized void run() throws IllegalArgumentException {
    if (PROFILER == null) {
      final Config config = Config.get();
      if (!config.isProfilingEnabled()) {
        log.info("Profiling: disabled");
        return;
      }
      if (config.getProfilingApiKey() == null) {
        log.info("Profiling: no API key, profiling disabled");
        return;
      }

      try {
        final Controller controller = ControllerFactory.createController();

        final RecordingUploader uploader =
            new RecordingUploader(
                config.getProfilingUrl(),
                config.getProfilingApiKey(),
                config.getMergedProfilingTags());

        PROFILER =
            new ProfilingSystem(
                controller,
                uploader::upload,
                Duration.ofSeconds(config.getProfilingPeriodicDelay()),
                Duration.ofSeconds(config.getProfilingPeriodicPeriod()),
                Duration.ofSeconds(config.getProfilingPeriodicDuration()));
        PROFILER.start();
        log.info("Periodic profiling has started!");
      } catch (final UnsupportedEnvironmentException | ConfigurationException e) {
        log.warn("Failed to initialize profiling agent!", e);
      }
    }
  }
}
