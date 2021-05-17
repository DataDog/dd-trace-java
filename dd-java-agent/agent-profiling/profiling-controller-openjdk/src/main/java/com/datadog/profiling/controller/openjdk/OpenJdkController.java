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

import static datadog.trace.api.Platform.isJavaVersionAtLeast;

import com.datadog.profiling.controller.ConfigurationException;
import com.datadog.profiling.controller.Controller;
import com.datadog.profiling.controller.jfr.JfpUtils;
import com.datadog.profiling.controller.openjdk.events.AvailableProcessorCoresEvent;
import datadog.trace.api.Config;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import jdk.jfr.Recording;
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
    try {
      recordingSettings =
          JfpUtils.readNamedJfpResource(
              JfpUtils.DEFAULT_JFP, config.getProfilingTemplateOverrideFile());
    } catch (final IOException e) {
      throw new ConfigurationException(e);
    }

    // Toggle settings based on JDK version
    if (Boolean.parseBoolean(recordingSettings.get("jdk.OldObjectSample#enabled"))) {
      if (!isJavaVersionAtLeast(17)) {
        log.debug(
            "Inexpensive live object profiling is not supported for this JDK. Disabling OldObjectSample JFR event.");
        recordingSettings.put("jdk.OldObjectSample#enabled", "false");
      }
    }

    // Register periodic events
    AvailableProcessorCoresEvent.register();
  }

  @Override
  public OpenJdkOngoingRecording createRecording(final String recordingName) {
    final Recording recording = new Recording();
    recording.setName(recordingName);
    recording.setSettings(recordingSettings);
    recording.setMaxSize(RECORDING_MAX_SIZE);
    recording.setMaxAge(RECORDING_MAX_AGE);
    recording.start();
    return new OpenJdkOngoingRecording(recording);
  }
}
