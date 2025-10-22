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

import com.datadog.profiling.controller.OngoingRecording;
import com.datadog.profiling.controller.ProfilerSettingsSupport;
import com.datadog.profiling.controller.UnsupportedEnvironmentException;
import com.datadog.profiling.ddprof.DatadogProfiler;
import datadog.environment.JavaVirtualMachine;
import datadog.trace.api.profiling.ProfilingSnapshot;
import datadog.trace.api.profiling.RecordingData;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressFBWarnings("CT_CONSTRUCTOR_THROW")
public class DatadogProfilerOngoingRecording implements OngoingRecording {
  private static final Logger log = LoggerFactory.getLogger(DatadogProfilerOngoingRecording.class);

  private final ProfilerSettingsSupport configMemento;

  private final OngoingRecording recording;
  private final Instant started = Instant.now();

  DatadogProfilerOngoingRecording(DatadogProfiler datadogProfiler, String recordingName)
      throws UnsupportedEnvironmentException {
    log.debug("Creating new recording: {}", recordingName);
    recording = datadogProfiler.start();
    if (recording == null) {
      throw new UnsupportedEnvironmentException("Failed to start Datadog profiler");
    }
    log.debug("Recording {} started", recordingName);
    this.configMemento =
        JavaVirtualMachine.isJ9() ? new DatadogProfilerSettings(datadogProfiler) : null;
  }

  @Override
  public RecordingData stop() {
    publishConfig();
    return recording.stop();
  }

  // @VisibleForTesting
  final RecordingData snapshot(final Instant start) {
    return snapshot(start, ProfilingSnapshot.Kind.PERIODIC);
  }

  @Override
  public RecordingData snapshot(final Instant start, ProfilingSnapshot.Kind kind) {
    publishConfig();
    return recording.snapshot(start, kind);
  }

  @Override
  public void close() {
    recording.close();
  }

  private void publishConfig() {
    if (configMemento != null) {
      configMemento.publish();
    }
  }
}
