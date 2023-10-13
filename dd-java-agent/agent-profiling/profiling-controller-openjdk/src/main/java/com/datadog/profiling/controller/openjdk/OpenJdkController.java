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
import static datadog.trace.api.Platform.isJavaVersionAtLeast;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ULTRA_MINIMAL;

import com.datadog.profiling.controller.ConfigurationException;
import com.datadog.profiling.controller.Controller;
import com.datadog.profiling.controller.UnsupportedEnvironmentException;
import com.datadog.profiling.controller.jfr.JfpUtils;
import com.datadog.profiling.controller.openjdk.events.AvailableProcessorCoresEvent;
import datadog.trace.api.Platform;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.IOException;
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

  private static final String EXPLICITLY_DISABLED = "explicitly disabled by user";
  private static final String EXPLICITLY_ENABLED = "explicitly enabled by user";
  private static final String EXPENSIVE_ON_CURRENT_JVM =
      "expensive on this version of the JVM (" + Platform.getRuntimeVersion() + ")";
  static final Duration RECORDING_MAX_AGE = Duration.ofMinutes(5);

  private static final Logger log = LoggerFactory.getLogger(OpenJdkController.class);

  private final Map<String, String> recordingSettings;

  /**
   * Main constructor for OpenJDK profiling controller.
   *
   * <p>This has to be public because it is created via reflection
   */
  @SuppressForbidden
  public OpenJdkController(final ConfigProvider configProvider)
      throws ConfigurationException, ClassNotFoundException {
    // Make sure we can load JFR classes before declaring that we have successfully created
    // factory and can use it.
    Class.forName("jdk.jfr.Recording");
    Class.forName("jdk.jfr.FlightRecorder");

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

    // Register periodic events
    AvailableProcessorCoresEvent.register();
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
        recordingName, recordingSettings, getMaxSize(), RECORDING_MAX_AGE);
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
}
