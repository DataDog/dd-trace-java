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
package com.datadog.profiling.controller.ddprof;

import com.datadog.profiling.controller.Controller;
import com.datadog.profiling.controller.UnsupportedEnvironmentException;
import com.datadog.profiling.ddprof.DatadogProfiler;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** This is the implementation of the controller for Async. */
public final class DatadogProfilerController implements Controller {
  static final Duration RECORDING_MAX_AGE = Duration.ofMinutes(5);

  private static final Logger log = LoggerFactory.getLogger(DatadogProfilerController.class);

  private final DatadogProfiler datadogProfiler;

  public static Controller instance(ConfigProvider configProvider) {
    return new DatadogProfilerController(configProvider);
  }

  /**
   * Main constructor for Async profiling controller.
   *
   * <p>This has to be public because it is created via reflection
   */
  public DatadogProfilerController() {
    this(DatadogProfiler.getInstance());
  }

  public DatadogProfilerController(ConfigProvider configProvider) {
    this(DatadogProfiler.newInstance(configProvider));
  }

  DatadogProfilerController(DatadogProfiler datadogProfiler) {
    this.datadogProfiler = datadogProfiler;
    assert datadogProfiler.isAvailable();
  }

  @Override
  public DatadogProfilerOngoingRecording createRecording(final String recordingName)
      throws UnsupportedEnvironmentException {
    return new DatadogProfilerOngoingRecording(datadogProfiler, recordingName);
  }
}
