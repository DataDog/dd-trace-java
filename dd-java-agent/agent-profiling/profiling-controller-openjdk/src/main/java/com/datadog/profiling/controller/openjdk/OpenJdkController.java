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

import com.datadog.profiling.controller.ConfigurationException;
import com.datadog.profiling.controller.Controller;
import com.datadog.profiling.controller.UnsupportedEnvironmentException;
import com.datadog.profiling.controller.jfr.JfpUtils;
import com.datadog.profiling.controller.openjdk.events.AvailableProcessorCoresEvent;
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

    Map<String, String> recordingSettings;

    try {
      recordingSettings = JfpUtils.readNamedJfpResource(JfpUtils.DEFAULT_JFP);
    } catch (final IOException e) {
      throw new ConfigurationException(e);
    }

    // Toggle settings based on JDK version

    if (isOldObjectSampleEnabledInRecordingSettings(recordingSettings)
        && !isOldObjectSampleAvailable()) {
      log.debug(
          "Inexpensive live object profiling is not supported for this JDK. "
              + "Disabling OldObjectSample JFR event.");
      recordingSettings.put("jdk.OldObjectSample#enabled", "false");
    }

    if (isObjectAllocationSampleEnabledInRecordingSettings(recordingSettings)
        && !isObjectAllocationSampleAvailable()) {
      log.debug(
          "Inexpensive allocation profiling is not supported for this JDK. "
              + "Disabling ObjectAllocationSample JFR event.");
      recordingSettings.put("jdk.ObjectAllocationSample#enabled", "false");
    }

    if (isNativeMethodSampleEnabledInRecordingSettings(recordingSettings)
        && !isNativeMethodSampleAvailable()) {
      log.debug(
          "Inexpensive native profiling is not supported for this JDK. "
              + "Disabling NativeMethodSample JFR event.");
      recordingSettings.put("jdk.NativeMethodSample#enabled", "false");
    }

    // Toggle settings from override file

    try {
      recordingSettings.putAll(
          JfpUtils.readOverrideJfpResource(
              configProvider.getString(ProfilingConfig.PROFILING_TEMPLATE_OVERRIDE_FILE)));
    } catch (final IOException e) {
      throw new ConfigurationException(e);
    }

    // Toggle settings from config

    if (isOldObjectSampleEnabledInConfig(configProvider)) {
      log.debug("Enabling OldObjectSample JFR event with the config.");
      recordingSettings.put("jdk.OldObjectSample#enabled", "true");
    }

    if (isObjectAllocationSampleEnabledInConfig(configProvider)) {
      if (isObjectAllocationSampleAvailable()) {
        // jdk.ObjectAllocationSample is available and enabled by default
      } else {
        log.debug(
            "Enabling ObjectAllocationInNewTLAB and ObjectAllocationOutsideTLAB JFR events with the config.");
        recordingSettings.put("jdk.ObjectAllocationInNewTLAB#enabled", "true");
        recordingSettings.put("jdk.ObjectAllocationOutsideTLAB#enabled", "true");
      }
    } else {
      if (isObjectAllocationSampleAvailable()) {
        log.debug("Disabling ObjectAllocationSample JFR event with the config.");
        recordingSettings.put("jdk.ObjectAllocationSample#enabled", "false");
      } else {
        // jdk.ObjectAllocationInNewTLAB and jdk.ObjectAllocationOutsideTLAB are disabled by default
      }
    }

    // Warn users for expensive events

    if (isOldObjectSampleEnabledInRecordingSettings(recordingSettings)
        && !isOldObjectSampleAvailable()) {
      log.warn("Inexpensive heap profiling is not supported for this JDK but is enabled.");
    }

    if (isObjectAllocationInNewTLABEnabledInRecordingSettings(recordingSettings)
        || isObjectAllocationOutsideTLABEnabledInRecordingSettings(recordingSettings)) {
      log.warn("Inexpensive allocation profiling is not supported for this JDK but is enabled.");
    }

    if (isNativeMethodSampleEnabledInRecordingSettings(recordingSettings)
        && !isNativeMethodSampleAvailable()) {
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

  // jdk.OldObjectSample

  boolean isOldObjectSampleEnabledInConfig(ConfigProvider configProvider) {
    return configProvider.getBoolean(
        ProfilingConfig.PROFILING_HEAP_ENABLED, ProfilingConfig.PROFILING_HEAP_ENABLED_DEFAULT);
  }

  boolean isOldObjectSampleEnabledInRecordingSettings(Map<String, String> recordingSettings) {
    return Boolean.parseBoolean(recordingSettings.get("jdk.OldObjectSample#enabled"));
  }

  // jdk.ObjectAllocationSample

  boolean isObjectAllocationSampleEnabledInConfig(ConfigProvider configProvider) {
    return configProvider.getBoolean(
        ProfilingConfig.PROFILING_ALLOCATION_ENABLED, isObjectAllocationSampleAvailable());
  }

  boolean isObjectAllocationSampleEnabledInRecordingSettings(
      Map<String, String> recordingSettings) {
    return Boolean.parseBoolean(recordingSettings.get("jdk.ObjectAllocationSample#enabled"));
  }

  // jdk.ObjectAllocationInNewTLAB

  boolean isObjectAllocationInNewTLABEnabledInRecordingSettings(
      Map<String, String> recordingSettings) {
    return Boolean.parseBoolean(recordingSettings.get("jdk.ObjectAllocationInNewTLAB#enabled"));
  }

  // jdk.ObjectAllocationOutsideTLAB

  boolean isObjectAllocationOutsideTLABEnabledInRecordingSettings(
      Map<String, String> recordingSettings) {
    return Boolean.parseBoolean(recordingSettings.get("jdk.ObjectAllocationOutsideTLAB#enabled"));
  }

  // jdk.NativeMethodSample

  boolean isNativeMethodSampleEnabledInRecordingSettings(Map<String, String> recordingSettings) {
    return Boolean.parseBoolean(recordingSettings.get("jdk.NativeMethodSample#enabled"));
  }
}
