package com.datadog.profiling.agent;

import static datadog.trace.api.config.ProfilingConfig.PROFILING_START_FORCE_FIRST;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_START_FORCE_FIRST_DEFAULT;
import static datadog.trace.util.AgentThreadFactory.AGENT_THREAD_GROUP;

import com.datadog.profiling.controller.ConfigurationException;
import com.datadog.profiling.controller.Controller;
import com.datadog.profiling.controller.ControllerFactory;
import com.datadog.profiling.controller.ProfilingSystem;
import com.datadog.profiling.controller.RecordingData;
import com.datadog.profiling.controller.RecordingDataListener;
import com.datadog.profiling.controller.RecordingType;
import com.datadog.profiling.controller.UnsupportedEnvironmentException;
import com.datadog.profiling.uploader.ProfileUploader;
import datadog.trace.api.Config;
import datadog.trace.api.Platform;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
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

  private static class DataDumper implements RecordingDataListener {
    private final Path path;

    DataDumper(Path path) {
      if (Files.notExists(path)) {
        try {
          Files.createDirectories(path);
        } catch (IOException e) {
          log.warn("Unable to crate the debug profile dump directory", e);
          this.path = null;
          return;
        }
      }
      if (!Files.isDirectory(path)) {
        log.warn("Profiler debug dump path must be an existing directory");
        this.path = null;
      } else {
        this.path = path;
      }
    }

    @Override
    public void onNewData(RecordingType type, RecordingData data, boolean handleSynchronously) {
      if (path == null) {
        return;
      }
      try {
        Path tmp = Files.createTempFile(path, "dd-profiler-debug-", ".jfr");
        Files.copy(data.getStream(), tmp, StandardCopyOption.REPLACE_EXISTING);
        log.debug("Debug profile stored as {}", tmp);
      } catch (IOException e) {
        log.debug("Unable to write debug profile dump", e);
      }
    }
  }

  /**
   * Main entry point into profiling Note: this must be reentrant because we may want to start
   * profiling before any other tool, and then attempt to start it again at normal time
   */
  public static synchronized void run(final boolean isStartingFirst, ClassLoader agentClasLoader)
      throws IllegalArgumentException, IOException {
    if (profiler == null) {
      final Config config = Config.get();
      final ConfigProvider configProvider = ConfigProvider.getInstance();

      boolean startForceFirst =
          configProvider.getBoolean(
              PROFILING_START_FORCE_FIRST, PROFILING_START_FORCE_FIRST_DEFAULT);

      if (!isStartForceFirstSafe()) {
        log.debug(
            "Starting profiling in premain can lead to crashes in this JDK. Delaying the startup.");
        startForceFirst = false;
      }

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

      try {
        final Controller controller = ControllerFactory.createController(configProvider);

        String dumpPath = configProvider.getString(ProfilingConfig.PROFILING_DEBUG_DUMP_PATH);
        DataDumper dumper = dumpPath != null ? new DataDumper(Paths.get(dumpPath)) : null;

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
                dumper == null
                    ? uploader::upload
                    : (type, data, sync) -> {
                      dumper.onNewData(type, data, sync);
                      uploader.upload(type, data, sync);
                    },
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
        log.warn("Failed to initialize profiling agent! {}", e.getMessage());
        log.debug("Failed to initialize profiling agent!", e);
      }
    }
  }

  private static boolean isStartForceFirstSafe() {
    return Platform.isJavaVersionAtLeast(14)
        || (Platform.isJavaVersion(13) && Platform.isJavaVersionAtLeast(13, 0, 4))
        || (Platform.isJavaVersion(11) && Platform.isJavaVersionAtLeast(11, 0, 8));
  }

  public static void shutdown() {
    shutdown(profiler, uploader, false);
  }

  public static void shutdown(boolean snapshot) {
    shutdown(profiler, uploader, snapshot);
  }

  private static final AtomicBoolean shutDownFlag = new AtomicBoolean();

  private static void shutdown(
      ProfilingSystem profiler, ProfileUploader uploader, boolean snapshot) {
    if (shutDownFlag.compareAndSet(false, true)) {
      if (profiler != null) {
        profiler.shutdown(snapshot);
      }

      if (uploader != null) {
        uploader.shutdown();
      }
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
