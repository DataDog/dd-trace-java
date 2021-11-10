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

import static datadog.trace.api.Platform.isJavaVersion;
import static datadog.trace.api.Platform.isJavaVersionAtLeast;

import com.datadog.profiling.controller.ConfigurationException;
import com.datadog.profiling.controller.Controller;
import com.datadog.profiling.controller.jfr.JfpUtils;
import com.datadog.profiling.controller.openjdk.events.AvailableProcessorCoresEvent;
import datadog.trace.api.Config;
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
  static final int RECORDING_MAX_SIZE = 64 * 1024 * 1024; // 64 megs
  static final Duration RECORDING_MAX_AGE = Duration.ofMinutes(5);

  private static final Logger log = LoggerFactory.getLogger(OpenJdkController.class);

  private final Map<String, String> recordingSettings;

  /**
   * Main constructor for OpenJDK profiling controller.
   *
   * <p>This has to be public because it is created via reflection
   */
  @SuppressForbidden
  public OpenJdkController(final Config config)
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

    if (Boolean.parseBoolean(recordingSettings.get("jdk.OldObjectSample#enabled"))) {
      if (!((isJavaVersion(11) && isJavaVersionAtLeast(11, 0, 12))
          || (isJavaVersion(15) && isJavaVersionAtLeast(15, 0, 4))
          || (isJavaVersion(16) && isJavaVersionAtLeast(16, 0, 2))
          || isJavaVersionAtLeast(17))) {
        log.debug(
            "Inexpensive live object profiling is not supported for this JDK. "
                + "Disabling OldObjectSample JFR event.");
        recordingSettings.put("jdk.OldObjectSample#enabled", "false");
      }
    }

    if (Boolean.parseBoolean(recordingSettings.get("jdk.ObjectAllocationInNewTLAB#enabled"))
        || Boolean.parseBoolean(recordingSettings.get("jdk.ObjectAllocationOutsideTLAB#enabled"))) {
      if (!(isJavaVersionAtLeast(16))) {
        log.debug(
            "Inexpensive allocation profiling is not supported for this JDK. "
                + "Disabling ObjectAllocationInNewTLAB and ObjectAllocationOutsideTLAB JFR events.");
        recordingSettings.put("jdk.ObjectAllocationInNewTLAB#enabled", "false");
        recordingSettings.put("jdk.ObjectAllocationOutsideTLAB#enabled", "false");
      }
    }

    if (Boolean.parseBoolean(recordingSettings.get("jdk.NativeMethodSample#enabled"))) {
      if (!((isJavaVersion(8) && isJavaVersionAtLeast(8, 0, 302)) || isJavaVersionAtLeast(11))) {
        log.debug(
            "Inexpensive native profiling is not supported for this JDK. "
                + "Disabling NativeMethodSample JFR event.");
        recordingSettings.put("jdk.NativeMethodSample#enabled", "false");
      }
    }

    // Toggle settings from override file

    try {
      recordingSettings.putAll(
          JfpUtils.readOverrideJfpResource(config.getProfilingTemplateOverrideFile()));
    } catch (final IOException e) {
      throw new ConfigurationException(e);
    }

    // Toggle settings from config

    if (config.isProfilingHeapEnabled()) {
      // TODO: when jdk.OldObjectSample is enabled by default in dd.jfp, uncomment the following
      // if (!Boolean.parseBoolean(recordingSettings.get("jdk.OldObjectSample#enabled"))) {
      //   if (((isJavaVersion(11) && isJavaVersionAtLeast(11, 0, 12))
      //       || (isJavaVersion(15) && isJavaVersionAtLeast(15, 0, 4))
      //       || (isJavaVersion(16) && isJavaVersionAtLeast(16, 0, 2))
      //       || isJavaVersionAtLeast(17))) {
      //     // It was enabled based on JDK version so disabled by override file
      //     log.warn(
      //         "The OldObjectSample JFR event is disabled with the override file but enabled with
      // the config.");
      //   }
      // }
      log.debug("Enabling OldObjectSample JFR event with the config.");
      recordingSettings.put("jdk.OldObjectSample#enabled", "true");
    }

    if (config.isProfilingAllocationEnabled()) {
      if (!Boolean.parseBoolean(recordingSettings.get("jdk.ObjectAllocationInNewTLAB#enabled"))
          || !Boolean.parseBoolean(
              recordingSettings.get("jdk.ObjectAllocationOutsideTLAB#enabled"))) {
        if (isJavaVersionAtLeast(16)) {
          // It was enabled based on JDK version so disabled by override file
          log.warn(
              "The ObjectAllocationInNewTLAB and ObjectAllocationOutsideTLAB JFR events are disabled with the override file but enabled with the config.");
        }
      }
      log.debug(
          "Enabling ObjectAllocationInNewTLAB and ObjectAllocationOutsideTLAB JFR events with the config.");
      recordingSettings.put("jdk.ObjectAllocationInNewTLAB#enabled", "true");
      recordingSettings.put("jdk.ObjectAllocationOutsideTLAB#enabled", "true");
    }

    this.recordingSettings = Collections.unmodifiableMap(recordingSettings);

    // Register periodic events
    AvailableProcessorCoresEvent.register();
  }

  @Override
  public OpenJdkOngoingRecording createRecording(final String recordingName) {
    return new OpenJdkOngoingRecording(
        recordingName, recordingSettings, RECORDING_MAX_SIZE, RECORDING_MAX_AGE);
  }
}
