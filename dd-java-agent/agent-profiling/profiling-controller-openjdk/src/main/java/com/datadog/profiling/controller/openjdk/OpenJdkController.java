/*
 * Copyright 2019 Datadog
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datadog.profiling.controller.openjdk;

import static com.datadog.profiling.controller.ProfilingSupport.*;
import static com.datadog.profiling.controller.ProfilingSupport.isObjectCountParallelized;
import static datadog.trace.api.Platform.isJavaVersionAtLeast;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DEBUG_CLEANUP_REPO;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DEBUG_CLEANUP_REPO_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_HEAP_HISTOGRAM_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_HEAP_HISTOGRAM_ENABLED_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_HEAP_HISTOGRAM_MODE;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_HEAP_HISTOGRAM_MODE_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ULTRA_MINIMAL;

import com.datadog.profiling.controller.ConfigurationException;
import com.datadog.profiling.controller.Controller;
import com.datadog.profiling.controller.UnsupportedEnvironmentException;
import com.datadog.profiling.controller.jfr.JFRAccess;
import com.datadog.profiling.controller.jfr.JfpUtils;
import com.datadog.profiling.controller.openjdk.events.AvailableProcessorCoresEvent;
import datadog.trace.api.Platform;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.bootstrap.instrumentation.jfr.exceptions.ExceptionProfiling;
import datadog.trace.util.PidHelper;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is the implementation of the controller for OpenJDK. It should work for JDK 11+ today, and
 * unmodified for JDK 8+ once JFR has been back-ported. The Oracle JDK implementation will be far
 * messier... ;)
 */
public final class OpenJdkController implements Controller {
  private static final Logger log = LoggerFactory.getLogger(OpenJdkController.class);

  private static final String EXPLICITLY_DISABLED = "explicitly disabled by user";
  private static final String EXPLICITLY_ENABLED = "explicitly enabled by user";
  private static final String EXPENSIVE_ON_CURRENT_JVM =
      "expensive on this version of the JVM (" + Platform.getRuntimeVersion() + ")";
  static final Duration RECORDING_MAX_AGE = Duration.ofMinutes(5);

  private final ConfigProvider configProvider;
  private final Map<String, String> recordingSettings;

  private final AtomicBoolean shouldCleanup = new AtomicBoolean(false);

  public static Controller instance(ConfigProvider configProvider) {
    try {
      return new OpenJdkController(configProvider);
    } catch (ConfigurationException | ClassNotFoundException e) {
      log.debug("Unable to create OpenJDK controller", e);
      return new MisconfiguredController(e);
    }
  }

  /**
   * Main constructor for OpenJDK profiling controller.
   *
   * <p>This has to be public because it is created via reflection
   */
  @SuppressForbidden
  public OpenJdkController(final ConfigProvider configProvider)
      throws ConfigurationException, ClassNotFoundException {
    // configure the JFR stackdepth before we try to load any JFR classes
    int requestedStackDepth = getConfiguredStackDepth(configProvider);
    if (JFRAccess.instance().setStackDepth(requestedStackDepth)) {
      // if we successfully set the stack depth, mark it as applied
      // this will allow emitting the settings event with the current stack depth
      JfrProfilerSettings.instance().markJfrStackDepthApplied();
    }
    boolean shouldCleanupJfrRepository =
        configProvider.getBoolean(
            PROFILING_DEBUG_CLEANUP_REPO, PROFILING_DEBUG_CLEANUP_REPO_DEFAULT);
    String jfrRepositoryBase = null;
    if (shouldCleanupJfrRepository) {
      jfrRepositoryBase = getJfrRepositoryBase(configProvider);
      JFRAccess.instance().setBaseLocation(jfrRepositoryBase + "/pid_" + PidHelper.getPid());
    }
    // Make sure we can load JFR classes before declaring that we have successfully created
    // factory and can use it.
    Class.forName("jdk.jfr.Recording");
    Class.forName("jdk.jfr.FlightRecorder");

    this.configProvider = configProvider;

    boolean ultraMinimal = configProvider.getBoolean(PROFILING_ULTRA_MINIMAL, false);

    Map<String, String> recordingSettings;

    try {
      if (shouldCleanupJfrRepository) {
        cleanupJfrRepositories(Paths.get(jfrRepositoryBase));
      }

      recordingSettings =
          JfpUtils.readNamedJfpResource(
              ultraMinimal ? JfpUtils.SAFEPOINTS_JFP : JfpUtils.DEFAULT_JFP);
    } catch (final IOException e) {
      throw new ConfigurationException(e);
    }

    // Toggle settings based on JDK version

    if (!isOldObjectSampleAvailable()) {
      disableEvent(recordingSettings, "jdk.OldObjectSample", EXPENSIVE_ON_CURRENT_JVM);
    }

    if (!isObjectAllocationSampleAvailable()) {
      disableEvent(recordingSettings, "jdk.ObjectAllocationSample", EXPENSIVE_ON_CURRENT_JVM);
    }

    if (!isNativeMethodSampleAvailable()) {
      disableEvent(recordingSettings, "jdk.NativeMethodSample", EXPENSIVE_ON_CURRENT_JVM);
    }

    if (!isJavaVersionAtLeast(17)) {
      disableEvent(recordingSettings, "jdk.ClassLoaderStatistics", EXPENSIVE_ON_CURRENT_JVM);
    }

    if (!isFileWriteDurationCorrect()) {
      disableEvent(recordingSettings, "jdk.FileWrite", EXPENSIVE_ON_CURRENT_JVM);
    }

    if (configProvider.getBoolean(
        PROFILING_HEAP_HISTOGRAM_ENABLED, PROFILING_HEAP_HISTOGRAM_ENABLED_DEFAULT)) {
      if (!isObjectCountParallelized()) {
        log.warn(
            "enabling Datadog heap histogram on JVM without an efficient implementation of the jdk.ObjectCount event. "
                + "This may increase p99 latency. Consider upgrading to JDK 17.0.9+ or 21+ to reduce latency impact.");
      }
      String mode =
          configProvider.getString(
              PROFILING_HEAP_HISTOGRAM_MODE, PROFILING_HEAP_HISTOGRAM_MODE_DEFAULT);
      if ("periodic".equalsIgnoreCase(mode)) {
        enableEvent(recordingSettings, "jdk.ObjectCount", "user enabled histogram heap collection");
      } else {
        enableEvent(
            recordingSettings, "jdk.ObjectCountAfterGC", "user enabled histogram heap collection");
      }
    }

    // Toggle settings from override file

    try {
      recordingSettings.putAll(
          JfpUtils.readOverrideJfpResource(
              configProvider.getString(ProfilingConfig.PROFILING_TEMPLATE_OVERRIDE_FILE)));
    } catch (final IOException e) {
      throw new ConfigurationException(e);
    }

    // Toggle settings from override args

    String disabledEventsArgs = configProvider.getString(ProfilingConfig.PROFILING_DISABLED_EVENTS);
    if (disabledEventsArgs != null && !disabledEventsArgs.isEmpty()) {
      for (String disabledEvent : disabledEventsArgs.trim().split(",")) {
        disableEvent(recordingSettings, disabledEvent, EXPLICITLY_DISABLED);
      }
    }

    String enabledEventsArgs = configProvider.getString(ProfilingConfig.PROFILING_ENABLED_EVENTS);
    if (enabledEventsArgs != null && !enabledEventsArgs.isEmpty()) {
      for (String enabledEvent : enabledEventsArgs.trim().split(",")) {
        enableEvent(recordingSettings, enabledEvent, EXPLICITLY_ENABLED);
      }
    }

    // Toggle settings from config

    if (configProvider.getBoolean(
        ProfilingConfig.PROFILING_HEAP_ENABLED, ProfilingConfig.PROFILING_HEAP_ENABLED_DEFAULT)) {
      log.debug("Enabling OldObjectSample JFR event with the config.");
      recordingSettings.put("jdk.OldObjectSample#enabled", "true");
    }

    if (configProvider.getBoolean(
        ProfilingConfig.PROFILING_ALLOCATION_ENABLED, isObjectAllocationSampleAvailable())) {
      // jdk.ObjectAllocationSample is available and enabled by default
      if (!isObjectAllocationSampleAvailable()) {
        log.debug(
            "Enabling ObjectAllocationInNewTLAB and ObjectAllocationOutsideTLAB JFR events with the config.");
        recordingSettings.put("jdk.ObjectAllocationInNewTLAB#enabled", "true");
        recordingSettings.put("jdk.ObjectAllocationOutsideTLAB#enabled", "true");
      }
    } else {
      // jdk.ObjectAllocationInNewTLAB and jdk.ObjectAllocationOutsideTLAB are disabled by default
      if (isObjectAllocationSampleAvailable()) {
        log.debug("Disabling ObjectAllocationSample JFR event with the config.");
        recordingSettings.put("jdk.ObjectAllocationSample#enabled", "false");
      }
    }

    // Warn users for expensive events

    if (!isOldObjectSampleAvailable()
        && isEventEnabled(recordingSettings, "jdk.OldObjectSample#enabled")) {
      log.warn("Inexpensive heap profiling is not supported for this JDK but is enabled.");
    }

    if (isEventEnabled(recordingSettings, "jdk.ObjectAllocationInNewTLAB")
        || isEventEnabled(recordingSettings, "jdk.ObjectAllocationOutsideTLAB")) {
      log.warn("Inexpensive allocation profiling is not supported for this JDK but is enabled.");
    }

    if (!isNativeMethodSampleAvailable()
        && isEventEnabled(recordingSettings, "jdk.NativeMethodSample")) {
      log.warn("Inexpensive native profiling is not supported for this JDK but is enabled.");
    }

    this.recordingSettings = Collections.unmodifiableMap(recordingSettings);

    if (isEventEnabled(this.recordingSettings, "datadog.ExceptionSample")) {
      ExceptionProfiling.getInstance().start();
    }

    // Register periodic events
    AvailableProcessorCoresEvent.register();
  }

  private static String getJfrRepositoryBase(ConfigProvider configProvider) {
    return configProvider.getString(
        ProfilingConfig.PROFILING_JFR_REPOSITORY_BASE,
        ProfilingConfig.PROFILING_JFR_REPOSITORY_BASE_DEFAULT);
  }

  private static void cleanupJfrRepositories(Path repositoryBase) {
    try {
      Files.walkFileTree(repositoryBase, new JfrCleanupVisitor(repositoryBase));
    } catch (IOException e) {
      if (log.isDebugEnabled()) {
        log.warn("Unable to cleanup old JFR repositories", e);
      } else {
        log.warn("Unable to cleanup old JFR repositories");
      }
    }
  }

  int getMaxSize() {
    return ConfigProvider.getInstance()
        .getInteger(
            ProfilingConfig.PROFILING_JFR_REPOSITORY_MAXSIZE,
            ProfilingConfig.PROFILING_JFR_REPOSITORY_MAXSIZE_DEFAULT);
  }

  @Override
  public OpenJdkOngoingRecording createRecording(final String recordingName)
      throws UnsupportedEnvironmentException {
    return new OpenJdkOngoingRecording(
        recordingName, recordingSettings, getMaxSize(), RECORDING_MAX_AGE, configProvider);
  }

  private static void disableEvent(
      Map<String, String> recordingSettings, String event, String reason) {
    String wasEnabled = recordingSettings.put(event + "#enabled", "false");
    if (Boolean.parseBoolean(wasEnabled)) {
      log.debug("Disabling JFR event {} because it is {}.", event, reason);
    }
  }

  private static void enableEvent(
      Map<String, String> recordingSettings, String event, String reason) {
    String wasEnabled = recordingSettings.put(event + "#enabled", "true");
    if (!Boolean.parseBoolean(wasEnabled)) {
      log.debug("Enabling JFR event {} because it is {}.", event, reason);
    }
  }

  private static boolean isEventEnabled(Map<String, String> recordingSettings, String event) {
    return Boolean.parseBoolean(recordingSettings.get(event + "#enabled"));
  }

  private int getConfiguredStackDepth(ConfigProvider configProvider) {
    return configProvider.getInteger(
        ProfilingConfig.PROFILING_STACKDEPTH, ProfilingConfig.PROFILING_STACKDEPTH_DEFAULT);
  }

  private static class JfrCleanupVisitor implements FileVisitor<Path> {
    private boolean shouldClean = false;

    private final Path root;
    private final Set<String> pidSet = PidHelper.getJavaPids();

    JfrCleanupVisitor(Path root) {
      this.root = root;
    }

    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
        throws IOException {
      if (dir.equals(root)) {
        return FileVisitResult.CONTINUE;
      }
      String fileName = dir.getFileName().toString();
      // the JFR repository directories are under <basedir>/pid_<pid>
      String pid = fileName.startsWith("pid_") ? fileName.substring(4) : null;
      shouldClean |= pid != null && !pidSet.contains(pid);
      if (shouldClean) {
        log.debug("Cleaning JFR repository under {}", dir);
      }
      return shouldClean ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
      if (file.toString().toLowerCase().endsWith(".jfr")) {
        Files.delete(file);
      }
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
      if (log.isDebugEnabled() && file.toString().toLowerCase().endsWith(".jfr")) {
        log.debug("Failed to delete file {}", file, exc);
      }
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
      if (shouldClean) {
        Files.delete(dir);
        String fileName = dir.getFileName().toString();
        // reset the flag only if we are done cleaning the top-level directory
        shouldClean = !fileName.startsWith("pid_");
      }
      return FileVisitResult.CONTINUE;
    }
  }
}
