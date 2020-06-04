package com.datadog.profiling.agent;

import com.datadog.profiling.controller.ConfigurationException;
import com.datadog.profiling.controller.Controller;
import com.datadog.profiling.controller.ControllerFactory;
import com.datadog.profiling.controller.ProfilingSystem;
import com.datadog.profiling.controller.UnsupportedEnvironmentException;
import com.datadog.profiling.uploader.ProfileUploader;
import datadog.trace.api.Config;
import datadog.trace.mlt.MethodLevelTracer;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/** Profiling agent implementation */
@Slf4j
public class ProfilingAgent {

  private static final Predicate<String> API_KEY_REGEX =
      Pattern.compile("^[0-9a-fA-F]{32}$").asPredicate();

  private static volatile ProfilingSystem profiler;

  /**
   * Main entry point into profiling Note: this must be reentrant because we may want to start
   * profiling before any other tool, and then attempt to start it again at normal time
   */
  public static synchronized void run(final boolean isStartingFirst)
      throws IllegalArgumentException {
    if (profiler == null) {
      final Config config = Config.get();
      if (isStartingFirst && !config.isProfilingStartForceFirst()) {
        log.debug("Profiling: not starting first");
        // early startup is disabled;
        return;
      }
      if (!config.isProfilingEnabled()) {
        log.info("Profiling: disabled");
        return;
      }
      if (config.getApiKey() == null) {
        log.info("Profiling: no API key. Profiling is disabled.");
        return;
      }
      if (!API_KEY_REGEX.test(config.getApiKey())) {
        log.info(
            "Profiling: API key doesn't match expected format, expected to get a 32 character hex string. Profiling is disabled. {} ",
            config.getApiKey());
        return;
      }

      try {
        final Controller controller = ControllerFactory.createController(config);
        final ProfileUploader uploader = new ProfileUploader(config);

        final Duration startupDelay = Duration.ofSeconds(config.getProfilingStartDelay());
        final Duration uploadPeriod = Duration.ofSeconds(config.getProfilingUploadPeriod());

        // Randomize startup delay for up to one upload period. Consider having separate setting for
        // this in the future
        final Duration startupDelayRandomRange = uploadPeriod;

        profiler =
            new ProfilingSystem(
                controller,
                uploader::upload,
                startupDelay,
                startupDelayRandomRange,
                uploadPeriod,
                config.isProfilingStartForceFirst());
        profiler.start();
        log.info("Profiling has started!");

        try {
          /*
          Note: shutdown hooks are tricky because JVM holds reference for them forever preventing
          GC for anything that is reachable from it.
          This means that if/when we implement functionality to manually shutdown profiler we would
          need to not forget to add code that removes this shutdown hook from JVM.
           */
          Runtime.getRuntime().addShutdownHook(new ShutdownHook(profiler, uploader));
        } catch (final IllegalStateException ex) {
          // The JVM is already shutting down.
        }
      } catch (final UnsupportedEnvironmentException e) {
        log.warn(e.getMessage());
        log.debug("", e);
      } catch (final ConfigurationException e) {
        log.warn("Failed to initialize profiling agent! " + e.getMessage());
        log.debug("Failed to initialize profiling agent!", e);
      }
    }
  }

  private static class ShutdownHook extends Thread {

    private final WeakReference<ProfilingSystem> profilerRef;
    private final WeakReference<ProfileUploader> uploaderRef;

    private ShutdownHook(final ProfilingSystem profiler, final ProfileUploader uploader) {
      profilerRef = new WeakReference<>(profiler);
      uploaderRef = new WeakReference<>(uploader);
    }

    @Override
    public void run() {
      final ProfilingSystem profiler = profilerRef.get();
      if (profiler != null) {
        profiler.shutdown();
      }

      final ProfileUploader uploader = uploaderRef.get();
      if (uploader != null) {
        uploader.shutdown();
      }

      MethodLevelTracer.shutdown();
    }
  }
}
