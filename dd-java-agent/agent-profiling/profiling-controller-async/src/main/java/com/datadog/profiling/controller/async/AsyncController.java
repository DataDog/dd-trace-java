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
package com.datadog.profiling.controller.async;

import static com.datadog.profiling.controller.ProfilingSupport.*;

import com.datadog.profiling.async.AsyncProfiler;
import com.datadog.profiling.controller.Controller;
import com.datadog.profiling.controller.UnsupportedEnvironmentException;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** This is the implementation of the controller for Async. */
public final class AsyncController implements Controller {
  static final Duration RECORDING_MAX_AGE = Duration.ofMinutes(5);

  private static final Logger log = LoggerFactory.getLogger(AsyncController.class);

  private final AsyncProfiler asyncProfiler;

  /**
   * Main constructor for Async profiling controller.
   *
   * <p>This has to be public because it is created via reflection
   */
  public AsyncController(final ConfigProvider configProvider)
      throws UnsupportedEnvironmentException {
    this(AsyncProfiler.getInstance());
  }

  AsyncController(AsyncProfiler asyncProfiler) throws UnsupportedEnvironmentException {
    this.asyncProfiler = asyncProfiler;
    assert asyncProfiler.isAvailable();
  }

  @Override
  public AsyncOngoingRecording createRecording(final String recordingName)
      throws UnsupportedEnvironmentException {
    return new AsyncOngoingRecording(asyncProfiler, recordingName);
  }
}
