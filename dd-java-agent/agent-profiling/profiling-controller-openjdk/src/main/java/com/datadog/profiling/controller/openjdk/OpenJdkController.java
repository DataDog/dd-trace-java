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

import com.datadog.profiling.controller.ConfigurationException;
import com.datadog.profiling.controller.Controller;
import datadog.trace.api.Config;
import java.io.IOException;
import java.util.Map;
import jdk.jfr.Recording;

/**
 * This is the implementation of the controller for OpenJDK. It should work for JDK 11+ today, and
 * unmodified for JDK 8+ once JFR has been back-ported. The Oracle JDK implementation will be far
 * messier... ;)
 */
public final class OpenJdkController implements Controller {
  // Visible for testing
  static final String JFP_PERIODIC = "jfr2/ddperiodic.jfp";
  // Visible for testing
  static final String JFP_CONTINUOUS = "jfr2/ddcontinuous.jfp";

  private final Map<String, String> continuousRecordingSettings;
  private final Map<String, String> periodicRecordingSettings;

  /**
   * Main constructor for OpenJDK profiling controller.
   *
   * <p>This has to be public because it is created via reflection
   */
  public OpenJdkController(final Config config)
      throws ConfigurationException, ClassNotFoundException {
    // Make sure we can load JFR classesbefore declaring that we have successfully created
    // factory and can use it.
    Class.forName("jdk.jfr.Recording");
    Class.forName("jdk.jfr.FlightRecorder");

    try {
      periodicRecordingSettings =
          JfpUtils.readNamedJfpResource(
              JFP_PERIODIC, config.getProfilingPeriodicConfigOverridePath());
      continuousRecordingSettings =
          JfpUtils.readNamedJfpResource(
              JFP_CONTINUOUS, config.getProfilingContinuousConfigOverridePath());
    } catch (final IOException e) {
      throw new ConfigurationException(e);
    }
  }

  @Override
  public OpenJdkOngoingRecording createPeriodicRecording(final String recordingName) {
    final Recording recording = new Recording();
    recording.setName(recordingName);
    recording.setSettings(periodicRecordingSettings);
    recording.start();
    return new OpenJdkOngoingRecording(recording);
  }

  @Override
  public OpenJdkOngoingRecording createContinuousRecording(final String recordingName) {
    final Recording recording = new Recording();
    recording.setName(recordingName);
    recording.setSettings(continuousRecordingSettings);
    recording.start();
    // probably limit maxSize/maxAge to something sensible, and configurable, here.
    return new OpenJdkOngoingRecording(recording);
  }
}
