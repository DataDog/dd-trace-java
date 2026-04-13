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
import static datadog.environment.JavaVirtualMachine.isJavaVersionAtLeast;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_CPU_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_CPU_ENABLED_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_EXCEPTION_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_EXCEPTION_ENABLED_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_HEAP_HISTOGRAM_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_HEAP_HISTOGRAM_ENABLED_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_HEAP_HISTOGRAM_MODE;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_HEAP_HISTOGRAM_MODE_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_IO_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_IO_ENABLED_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_LOCK_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_LOCK_ENABLED_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_QUEUEING_TIME_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_QUEUEING_TIME_ENABLED_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_QUEUEING_TIME_THRESHOLD_MILLIS;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_QUEUEING_TIME_THRESHOLD_MILLIS_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_THREAD_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_THREAD_ENABLED_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ULTRA_MINIMAL;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_WALL_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_WALL_ENABLED_DEFAULT;

import com.datadog.profiling.controller.ConfigurationException;
import com.datadog.profiling.controller.Controller;
import com.datadog.profiling.controller.ControllerContext;
import com.datadog.profiling.controller.jfr.JFRAccess;
import com.datadog.profiling.controller.jfr.JfpUtils;
import com.datadog.profiling.controller.openjdk.events.AvailableProcessorCoresEvent;
import datadog.environment.JavaVirtualMachine;
import datadog.environment.OperatingSystem;
import datadog.trace.api.Config;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.bootstrap.instrumentation.jfr.backpressure.BackpressureProfiling;
import datadog.trace.bootstrap.instrumentation.jfr.exceptions.ExceptionProfiling;
import datadog.trace.util.TempLocationManager;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
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
      "expensive on this version of the JVM (" + JavaVirtualMachine.getRuntimeVersion() + ")";
  private static final String CPUTIME_SAMPLE_JDK25 = "Switching to CPUTimeSample on JDK 25+";

  static final Duration RECORDING_MAX_AGE = Duration.ofMinutes(5);

  private final ConfigProvider configProvider;
  private final Map<String, String> recordingSettings;
  private final boolean jfrStackDepthApplied;

  public static Controller instance(ConfigProvider configProvider)
      throws ConfigurationException, ClassNotFoundException {
    return new OpenJdkController(configProvider);
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
    this.jfrStackDepthApplied = JFRAccess.instance().setStackDepth(requestedStackDepth);
    String jfrRepositoryBase = getJfrRepositoryBase(configProvider);
    JFRAccess.instance().setBaseLocation(jfrRepositoryBase);
    // Make sure we can load JFR classes before declaring that we have successfully created
    // factory and can use it.
    Class.forName("jdk.jfr.Recording");
    Class.forName("jdk.jfr.FlightRecorder");

    this.configProvider = configProvider;

    boolean ultraMinimal = configProvider.getBoolean(PROFILING_ULTRA_MINIMAL, false);

    Map<String, String> recordingSettings;

    try {
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

    if (configProvider.getBoolean(
        PROFILING_QUEUEING_TIME_ENABLED, PROFILING_QUEUEING_TIME_ENABLED_DEFAULT)) {
      long threshold =
          configProvider.getLong(
              PROFILING_QUEUEING_TIME_THRESHOLD_MILLIS,
              PROFILING_QUEUEING_TIME_THRESHOLD_MILLIS_DEFAULT);
      recordingSettings.put("datadog.QueueTime#threshold", threshold + " ms");
    }

    // Toggle settings from override file

    try {
      recordingSettings.putAll(
          JfpUtils.readOverrideJfpResource(
              configProvider.getString(ProfilingConfig.PROFILING_TEMPLATE_OVERRIDE_FILE)));
    } catch (final IOException e) {
      throw new ConfigurationException(e);
    }

    // switch to CPUTimeSample event on JDK 25 and Linux
    if (JavaVirtualMachine.isJavaVersionAtLeast(25) && OperatingSystem.isLinux()) {
      disableEvent(recordingSettings, "jdk.ExecutionSample", CPUTIME_SAMPLE_JDK25);
      enableEvent(recordingSettings, "jdk.CPUTimeSample", CPUTIME_SAMPLE_JDK25);
      enableEvent(recordingSettings, "jdk.CPUTimeSamplesLost", CPUTIME_SAMPLE_JDK25);
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

    // Unified live heap (profiling.heap.enabled): when explicitly disabled, turn off
    // OldObjectSample. When enabled (or default), if ddprof is likely handling live heap,
    // proactively disable OldObjectSample to avoid double collection.
    // disableOverriddenEvents() at recording start is the definitive safety net,
    // but we disable here too so the settings map is consistent from the start.
    if (!configProvider.getBoolean(
        ProfilingConfig.PROFILING_HEAP_ENABLED, isLiveHeapProfilingSafe())) {
      disableEvent(recordingSettings, "jdk.OldObjectSample", "live heap profiling is disabled");
    } else {
      // ddprof live heap requires Java 11+ (JVMTI Allocation Sampler)
      // isJmethodIDSafe() matches ddprof's own default for liveheap: it only enables
      // MEMLEAK mode by default on versions where jmethodID is safe.
      boolean ddprofLikelyActive =
          isJavaVersionAtLeast(11)
              && configProvider.getBoolean(
                  ProfilingConfig.PROFILING_DATADOG_PROFILER_LIVEHEAP_ENABLED, isJmethodIDSafe())
              && configProvider.getBoolean(
                  ProfilingConfig.PROFILING_DATADOG_PROFILER_ENABLED, true);
      if (ddprofLikelyActive) {
        disableEvent(
            recordingSettings,
            "jdk.OldObjectSample",
            "ddprof live heap profiling is expected to handle live heap data");
      } else if (isOldObjectSampleAvailable()) {
        enableEvent(recordingSettings, "jdk.OldObjectSample", "heap profiling is enabled");
      } else if (configProvider.getBoolean(ProfilingConfig.PROFILING_HEAP_ENABLED, false)) {
        log.warn(
            "Live heap profiling was explicitly requested but is not supported on this JVM version;"
                + " no heap profiling data will be collected.");
      }
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

    if (configProvider.getBoolean(
        ProfilingConfig.PROFILING_SMAP_COLLECTION_ENABLED,
        ProfilingConfig.PROFILING_SMAP_COLLECTION_ENABLED_DEFAULT)) {
      enableEvent(
          recordingSettings, "datadog.SmapEntry", "Smaps collection is enabled in the config");
    } else {
      disableEvent(
          recordingSettings, "datadog.SmapEntry", "Smaps collection is disabled in the config");
    }
    if (configProvider.getBoolean(
        ProfilingConfig.PROFILING_SMAP_AGGREGATION_ENABLED,
        ProfilingConfig.PROFILING_SMAP_AGGREGATION_ENABLED_DEFAULT)) {
      enableEvent(
          recordingSettings,
          "datadog.AggregatedSmapEntry",
          "Aggregated smaps collection is enabled in the config");
    } else {
      disableEvent(
          recordingSettings,
          "datadog.AggregatedSmapEntry",
          "Aggregated smaps collection is disabled in the config");
    }

    // Feature gates — unified profiling.*.enabled keys

    if (!configProvider.getBoolean(PROFILING_CPU_ENABLED, PROFILING_CPU_ENABLED_DEFAULT)) {
      disableEvent(recordingSettings, "jdk.ExecutionSample", "CPU profiling is disabled");
      disableEvent(recordingSettings, "jdk.NativeMethodSample", "CPU profiling is disabled");
      disableEvent(recordingSettings, "jdk.CPUTimeSample", "CPU profiling is disabled");
      disableEvent(recordingSettings, "jdk.CPUTimeSamplesLost", "CPU profiling is disabled");
    }

    if (!configProvider.getBoolean(PROFILING_WALL_ENABLED, PROFILING_WALL_ENABLED_DEFAULT)) {
      disableEvent(recordingSettings, "jdk.JavaMonitorWait", "wall-clock profiling is disabled");
      disableEvent(recordingSettings, "jdk.ThreadPark", "wall-clock profiling is disabled");
      disableEvent(recordingSettings, "jdk.ThreadSleep", "wall-clock profiling is disabled");
    }

    if (!configProvider.getBoolean(
        PROFILING_EXCEPTION_ENABLED, PROFILING_EXCEPTION_ENABLED_DEFAULT)) {
      disableEvent(recordingSettings, "datadog.ExceptionSample", "exception profiling is disabled");
      disableEvent(recordingSettings, "datadog.ExceptionCount", "exception profiling is disabled");
    }

    if (!configProvider.getBoolean(PROFILING_IO_ENABLED, PROFILING_IO_ENABLED_DEFAULT)) {
      disableEvent(recordingSettings, "jdk.FileRead", "I/O profiling is disabled");
      disableEvent(recordingSettings, "jdk.FileWrite", "I/O profiling is disabled");
      disableEvent(recordingSettings, "jdk.FileForce", "I/O profiling is disabled");
      disableEvent(recordingSettings, "jdk.SocketRead", "I/O profiling is disabled");
      disableEvent(recordingSettings, "jdk.SocketWrite", "I/O profiling is disabled");
    }

    if (!configProvider.getBoolean(PROFILING_LOCK_ENABLED, PROFILING_LOCK_ENABLED_DEFAULT)) {
      disableEvent(recordingSettings, "jdk.JavaMonitorEnter", "lock profiling is disabled");
      disableEvent(recordingSettings, "jdk.BiasedLockRevocation", "lock profiling is disabled");
      disableEvent(recordingSettings, "jdk.BiasedLockSelfRevocation", "lock profiling is disabled");
      disableEvent(
          recordingSettings, "jdk.BiasedLockClassRevocation", "lock profiling is disabled");
    }

    if (!configProvider.getBoolean(PROFILING_THREAD_ENABLED, PROFILING_THREAD_ENABLED_DEFAULT)) {
      disableEvent(recordingSettings, "jdk.ThreadStart", "thread profiling is disabled");
      disableEvent(recordingSettings, "jdk.ThreadEnd", "thread profiling is disabled");
    }

    // Warn users for expensive events

    if (!isOldObjectSampleAvailable() && isEventEnabled(recordingSettings, "jdk.OldObjectSample")) {
      log.warn("JFR based live heap profiling is not supported for this JDK but is enabled.");
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

    if (Config.get().isProfilingBackPressureSamplingEnabled()) {
      BackpressureProfiling.getInstance().start();
    }

    // Register periodic events
    AvailableProcessorCoresEvent.register();

    log.debug("JFR Recording Settings: {}", recordingSettings);
  }

  private static String getJfrRepositoryBase(ConfigProvider configProvider) {
    String legacy =
        configProvider.getString(
            ProfilingConfig.PROFILING_JFR_REPOSITORY_BASE,
            ProfilingConfig.PROFILING_JFR_REPOSITORY_BASE_DEFAULT);
    if (!legacy.equals(ProfilingConfig.PROFILING_JFR_REPOSITORY_BASE_DEFAULT)) {
      log.warn(
          "The configuration key {} is deprecated. Please use {} instead.",
          ProfilingConfig.PROFILING_JFR_REPOSITORY_BASE,
          ProfilingConfig.PROFILING_TEMP_DIR);
    }
    TempLocationManager tempLocationManager = TempLocationManager.getInstance();
    Path repositoryPath = tempLocationManager.getTempDir().resolve("jfr");
    if (!Files.exists(repositoryPath)) {
      try {
        Files.createDirectories(repositoryPath);
      } catch (IOException e) {
        log.error("Failed to create JFR repository directory: {}", repositoryPath, e);
        throw new IllegalStateException(
            "Failed to create JFR repository directory: " + repositoryPath, e);
      }
    }
    return repositoryPath.toString();
  }

  int getMaxSize() {
    return ConfigProvider.getInstance()
        .getInteger(
            ProfilingConfig.PROFILING_JFR_REPOSITORY_MAXSIZE,
            ProfilingConfig.PROFILING_JFR_REPOSITORY_MAXSIZE_DEFAULT);
  }

  @Override
  public OpenJdkOngoingRecording createRecording(
      final String recordingName, ControllerContext.Snapshot context) {
    return new OpenJdkOngoingRecording(
        recordingName,
        recordingSettings,
        getMaxSize(),
        RECORDING_MAX_AGE,
        configProvider,
        context,
        jfrStackDepthApplied);
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
}
