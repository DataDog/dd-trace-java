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
import com.datadog.profiling.controller.ControllerContext;
import com.datadog.profiling.controller.OngoingRecording;
import com.datadog.profiling.controller.UnsupportedEnvironmentException;
import com.datadog.profiling.ddprof.DatadogProfiler;
import datadog.config.ConfigProvider;
import java.util.EnumSet;
import javax.annotation.Nonnull;

/** This is the implementation of the controller for DD profiler. */
public final class DatadogProfilerController implements Controller {

  private final DatadogProfiler datadogProfiler;

  public static Controller instance(ConfigProvider configProvider) throws Throwable {
    return new DatadogProfilerController(configProvider);
  }

  public DatadogProfilerController(ConfigProvider configProvider) {
    this(DatadogProfiler.newInstance(configProvider));
  }

  DatadogProfilerController(DatadogProfiler datadogProfiler) {
    this.datadogProfiler = datadogProfiler;
  }

  @Nonnull
  @Override
  public OngoingRecording createRecording(
      @Nonnull String recordingName, ControllerContext.Snapshot context)
      throws UnsupportedEnvironmentException {
    return new DatadogProfilerOngoingRecording(datadogProfiler, recordingName);
  }

  @Override
  public void configure(ControllerContext context) {
    context.setDatadogProfilerEnabled(true);
    context.setDatadogProfilingModes(EnumSet.copyOf(datadogProfiler.enabledModes()));
  }
}
