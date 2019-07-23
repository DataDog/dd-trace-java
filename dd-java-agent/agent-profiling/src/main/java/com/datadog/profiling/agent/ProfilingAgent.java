package com.datadog.profiling.agent;

import com.datadog.profiling.controller.ConfigurationException;
import com.datadog.profiling.controller.Controller;
import com.datadog.profiling.controller.ControllerFactory;
import com.datadog.profiling.controller.ProfilingSystem;
import com.datadog.profiling.controller.UnsupportedEnvironmentException;
import com.datadog.profiling.uploader.RecordingUploader;
import datadog.trace.api.Config;
import java.lang.ref.WeakReference;
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
        final Controller controller = ControllerFactory.createController(config);

        final RecordingUploader uploader =
            new RecordingUploader(
                config.getProfilingUrl(),
                config.getProfilingApiKey(),
                config.getMergedProfilingTags(),
                Duration.ofSeconds(config.getProfilingUploadRequestTimeout()),
                Duration.ofSeconds(config.getProfilingUploadRequestIOTimeout()));

        PROFILER =
            new ProfilingSystem(
                controller,
                uploader::upload,
                Duration.ofSeconds(config.getProfilingStartupDelay()),
                Duration.ofSeconds(config.getProfilingUploadPeriod()),
                config.getProfilingContinuousToPeriodicUploadsRatio());
        PROFILER.start();
        log.info("Periodic profiling has started!");

        try {
          /*
          Note: shutdown hooks are tricky because JVM holds reference for them forever preventing
          GC for anything that is reachable from it.
          This means that if/when we implement functionality to manually shutdown profiler we would
          need to not forget to add code that removes this shutdown hook from JVM.
           */
          Runtime.getRuntime().addShutdownHook(new ShutdownHook(PROFILER, uploader));
        } catch (final IllegalStateException ex) {
          // The JVM is already shutting down.
        }
      } catch (final UnsupportedEnvironmentException | ConfigurationException e) {
        log.warn("Failed to initialize profiling agent!", e);
      }
    }
  }

  private static class ShutdownHook extends Thread {
    private final WeakReference<ProfilingSystem> profilerRef;
    private final WeakReference<RecordingUploader> uploaderRef;

    private ShutdownHook(final ProfilingSystem profiler, final RecordingUploader uploader) {
      profilerRef = new WeakReference<>(profiler);
      uploaderRef = new WeakReference<>(uploader);
    }

    @Override
    public void run() {
      final ProfilingSystem profiler = profilerRef.get();
      if (profiler != null) {
        profiler.shutdown();
      }

      final RecordingUploader uploader = uploaderRef.get();
      if (uploader != null) {
        uploader.shutdown();
      }
    }
  }
}
