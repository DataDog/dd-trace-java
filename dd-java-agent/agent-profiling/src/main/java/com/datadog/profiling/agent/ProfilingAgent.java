package com.datadog.profiling.agent;

import static datadog.environment.JavaVirtualMachine.isJavaVersion;
import static datadog.environment.JavaVirtualMachine.isJavaVersionAtLeast;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_SCRUB_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_SCRUB_ENABLED_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_SCRUB_FAIL_OPEN;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_SCRUB_FAIL_OPEN_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_START_FORCE_FIRST;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_START_FORCE_FIRST_DEFAULT;
import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;
import static datadog.trace.util.AgentThreadFactory.AGENT_THREAD_GROUP;

import com.datadog.profiling.controller.ConfigurationException;
import com.datadog.profiling.controller.Controller;
import com.datadog.profiling.controller.ControllerContext;
import com.datadog.profiling.controller.ProfilerFlareReporter;
import com.datadog.profiling.controller.ProfilingSystem;
import com.datadog.profiling.controller.UnsupportedEnvironmentException;
import com.datadog.profiling.controller.jfr.JFRAccess;
import com.datadog.profiling.uploader.ProfileUploader;
import com.datadog.profiling.utils.Timestamper;
import datadog.trace.api.Config;
import datadog.trace.api.Platform;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.api.profiling.ProfilerFlareLogger;
import datadog.trace.api.profiling.RecordingData;
import datadog.trace.api.profiling.RecordingDataListener;
import datadog.trace.api.profiling.RecordingType;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
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
  public static synchronized boolean run(final boolean earlyStart, Instrumentation inst)
      throws IllegalArgumentException, IOException {
    if (profiler == null) {
      final Config config = Config.get();
      final ConfigProvider configProvider = ConfigProvider.getInstance();

      // Register the profiler flare before we start the profiling system, but early during the
      // profiler lifecycle
      ProfilerFlareReporter.register();
      ProcessContext.register(configProvider);

      boolean startForceFirst =
          Platform.isNativeImage()
              || configProvider.getBoolean(
                  PROFILING_START_FORCE_FIRST, PROFILING_START_FORCE_FIRST_DEFAULT);

      if (!isStartForceFirstSafe()) {
        log.debug(
            "Starting profiling in premain can lead to crashes in this JDK. Delaying the startup.");
        startForceFirst = false;
      }

      if (earlyStart && !startForceFirst) {
        log.debug("Profiling: not starting first");
        // early startup is disabled;
        return true;
      }
      if (!config.isProfilingEnabled()) {
        log.debug(SEND_TELEMETRY, "Profiling: disabled");
        return false;
      }
      if (config.getApiKey() != null && !API_KEY_REGEX.test(config.getApiKey())) {
        log.info(
            "Profiling: API key doesn't match expected format, expected to get a 32 character hex string. Profiling is disabled.");
        return false;
      }

      try {
        JFRAccess.setup(inst);
        Timestamper.override(JFRAccess.instance());
        ControllerContext context = new ControllerContext();
        final Controller controller = CompositeController.build(configProvider, context);

        String dumpPath = configProvider.getString(ProfilingConfig.PROFILING_DEBUG_DUMP_PATH);
        DataDumper dumper = dumpPath != null ? new DataDumper(Paths.get(dumpPath)) : null;

        uploader = new ProfileUploader(config, configProvider);

        RecordingDataListener listener = uploader::upload;
        if (dumper != null) {
          RecordingDataListener upload = listener;
          listener =
              (type, data, sync) -> {
                dumper.onNewData(type, data, sync);
                upload.onNewData(type, data, sync);
              };
        }
        // Scrubber wraps the combined dumper+uploader so debug dumps also contain scrubbed data
        if (configProvider.getBoolean(PROFILING_SCRUB_ENABLED, PROFILING_SCRUB_ENABLED_DEFAULT)) {
          List<String> excludeEventTypes =
              configProvider.getList(ProfilingConfig.PROFILING_SCRUB_EXCLUDE_EVENTS);
          boolean failOpen =
              configProvider.getBoolean(
                  PROFILING_SCRUB_FAIL_OPEN, PROFILING_SCRUB_FAIL_OPEN_DEFAULT);
          listener = wrapWithScrubber(listener, excludeEventTypes, failOpen);
        }

        final Duration startupDelay = Duration.ofSeconds(config.getProfilingStartDelay());
        final Duration uploadPeriod = Duration.ofSeconds(config.getProfilingUploadPeriod());

        // Randomize startup delay for up to one upload period. Consider having separate setting for
        // this in the future
        final Duration startupDelayRandomRange = uploadPeriod;

        profiler =
            new ProfilingSystem(
                configProvider,
                controller,
                context.snapshot(),
                listener,
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
      } catch (final UnsupportedEnvironmentException | ConfigurationException e) {
        ProfilerFlareLogger.getInstance().log("Failed to initialize profiling agent!", e);
        ProfilerFlareReporter.reportInitializationException(e);
      }
    }
    return false;
  }

  /**
   * Wraps a listener with the JFR scrubber using reflection to avoid a compile-time dependency on
   * {@code ScrubRecordingDataListener} and its transitive jafar-parser classes. A direct reference
   * would cause {@code NoClassDefFoundError} during GraalVM native-image builds when the
   * VMRuntimeModule's helper injector walks transitive dependencies of this class.
   */
  // Class.forName is safe here — the target class lives in the same classloader (agent-profiling
  // shadow jar). We use reflection solely to avoid a compile-time type reference that would cause
  // GraalVM native-image to walk jafar-parser's transitive dependencies.
  @SuppressForbidden
  private static RecordingDataListener wrapWithScrubber(
      RecordingDataListener listener, List<String> excludeEventTypes, boolean failOpen) {
    try {
      Class<?> scrubClass = Class.forName("com.datadog.profiling.agent.ScrubRecordingDataListener");
      return (RecordingDataListener)
          scrubClass
              .getDeclaredMethod("wrap", RecordingDataListener.class, List.class, boolean.class)
              .invoke(null, listener, excludeEventTypes, failOpen);
    } catch (Exception e) {
      log.warn(SEND_TELEMETRY, "Failed to initialize JFR scrubber", e);
      return listener;
    }
  }

  private static boolean isStartForceFirstSafe() {
    return isJavaVersionAtLeast(14)
        || (isJavaVersion(13) && isJavaVersionAtLeast(13, 0, 4))
        || (isJavaVersion(11) && isJavaVersionAtLeast(11, 0, 8));
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
