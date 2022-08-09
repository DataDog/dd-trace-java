package com.datadog.profiling.agent;

import static datadog.trace.api.config.ProfilingConfig.PROFILING_START_FORCE_FIRST;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_START_FORCE_FIRST_DEFAULT;
import static datadog.trace.util.AgentThreadFactory.AGENT_THREAD_GROUP;

import com.datadog.profiling.context.AsyncProfilerTracingContextTrackerFactory;
import com.datadog.profiling.context.JfrTimestampPatch;
import com.datadog.profiling.context.PerSpanTracingContextTrackerFactory;
import com.datadog.profiling.controller.ConfigurationException;
import com.datadog.profiling.controller.Controller;
import com.datadog.profiling.controller.ControllerFactory;
import com.datadog.profiling.controller.ProfilingSystem;
import com.datadog.profiling.controller.UnsupportedEnvironmentException;
import com.datadog.profiling.uploader.ProfileUploader;
import datadog.trace.api.Config;
import datadog.trace.api.Platform;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Profiling agent implementation */
public class ProfilingAgent {

  private static final Logger log = LoggerFactory.getLogger(ProfilingAgent.class);

  private static final Predicate<String> API_KEY_REGEX =
      Pattern.compile("^[0-9a-fA-F]{32}$").asPredicate();

  private static volatile ProfilingSystem profiler;
  private static volatile ProfileUploader uploader;

  /**
   * Main entry point into profiling Note: this must be reentrant because we may want to start
   * profiling before any other tool, and then attempt to start it again at normal time
   */
  public static synchronized void run(final boolean isStartingFirst, ClassLoader agentClasLoader)
      throws IllegalArgumentException, IOException {
    if (profiler == null) {
      final Config config = Config.get();
      final ConfigProvider configProvider = ConfigProvider.getInstance();

      final boolean startForceFirst =
          configProvider.getBoolean(
              PROFILING_START_FORCE_FIRST, PROFILING_START_FORCE_FIRST_DEFAULT);

      if (isStartingFirst && !startForceFirst) {
        log.debug("Profiling: not starting first");
        // early startup is disabled;
        return;
      }
      if (!config.isProfilingEnabled()) {
        log.debug("Profiling: disabled");
        return;
      }
      if (config.getApiKey() != null && !API_KEY_REGEX.test(config.getApiKey())) {
        log.info(
            "Profiling: API key doesn't match expected format, expected to get a 32 character hex string. Profiling is disabled.");
        return;
      }
      if (Platform.isJavaVersionAtLeast(9)) {
        JfrTimestampPatch.execute(agentClasLoader);
      }

      try {
        final Controller controller = ControllerFactory.createController(configProvider);

        if (AsyncProfilerTracingContextTrackerFactory.isEnabled(configProvider)) {
          AsyncProfilerTracingContextTrackerFactory.register(configProvider);
        } else if (PerSpanTracingContextTrackerFactory.isEnabled(configProvider)) {
          PerSpanTracingContextTrackerFactory.register(configProvider);
        }

        uploader = new ProfileUploader(config, configProvider);

        final Duration startupDelay = Duration.ofSeconds(config.getProfilingStartDelay());
        final Duration uploadPeriod = Duration.ofSeconds(config.getProfilingUploadPeriod());

        // Randomize startup delay for up to one upload period. Consider having separate setting for
        // this in the future
        final Duration startupDelayRandomRange = uploadPeriod;

        profiler =
            new ProfilingSystem(
                configProvider,
                controller,
                uploader::upload,
                startupDelay,
                startupDelayRandomRange,
                uploadPeriod,
                startForceFirst);
        profiler.start();
        log.debug("Profiling has started");

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

  public static void shutdown() {
    shutdown(profiler, uploader, false);
  }

  public static void shutdown(boolean snapshot) {
    shutdown(profiler, uploader, snapshot);
  }

  private static void shutdown(
      ProfilingSystem profiler, ProfileUploader uploader, boolean snapshot) {
    if (profiler != null) {
      profiler.shutdown(snapshot);
    }

    if (uploader != null) {
      uploader.shutdown();
    }
  }

  private static class ShutdownHook extends Thread {

    private final WeakReference<ProfilingSystem> profilerRef;
    private final WeakReference<ProfileUploader> uploaderRef;

    private ShutdownHook(final ProfilingSystem profiler, final ProfileUploader uploader) {
      super(AGENT_THREAD_GROUP, "dd-profiler-shutdown-hook");
      profilerRef = new WeakReference<>(profiler);
      uploaderRef = new WeakReference<>(uploader);
    }

    @Override
    public void run() {
      shutdown(profilerRef.get(), uploaderRef.get(), false);
    }
  }
}
