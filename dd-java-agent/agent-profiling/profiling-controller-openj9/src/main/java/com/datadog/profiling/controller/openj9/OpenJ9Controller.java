/*
 * Copyright 2022 Datadog
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
package com.datadog.profiling.controller.openj9;

import static com.datadog.profiling.controller.ProfilingSupport.*;

import com.datadog.profiling.auxiliary.async.AsyncProfiler;
import com.datadog.profiling.controller.Controller;
import com.datadog.profiling.controller.UnsupportedEnvironmentException;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** This is the implementation of the controller for OpenJ9. */
public final class OpenJ9Controller implements Controller {
  static final Duration RECORDING_MAX_AGE = Duration.ofMinutes(5);

  private static final Logger log = LoggerFactory.getLogger(OpenJ9Controller.class);

  private final AsyncProfiler asyncProfiler;

  /**
   * Main constructor for OpenJ9 profiling controller.
   *
   * <p>This has to be public because it is created via reflection
   */
  public OpenJ9Controller(final ConfigProvider configProvider)
      throws UnsupportedEnvironmentException {
    this(AsyncProfiler.getInstance());
  }

  OpenJ9Controller(AsyncProfiler asyncProfiler) throws UnsupportedEnvironmentException {
    this.asyncProfiler = asyncProfiler;
    if (!asyncProfiler.isAvailable()) {
      throw new UnsupportedEnvironmentException(
          "An auxiliary profiler must be enabled with OpenJ9");
    }
  }

  @Override
  public OpenJ9OngoingRecording createRecording(final String recordingName) throws Exception {
    return new OpenJ9OngoingRecording(asyncProfiler, recordingName);
  }
}
