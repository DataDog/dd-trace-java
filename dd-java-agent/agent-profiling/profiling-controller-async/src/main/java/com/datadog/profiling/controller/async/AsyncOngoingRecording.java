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

import com.datadog.profiling.async.AsyncProfiler;
import com.datadog.profiling.controller.OngoingRecording;
import com.datadog.profiling.controller.RecordingData;
import com.datadog.profiling.controller.UnsupportedEnvironmentException;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AsyncOngoingRecording implements OngoingRecording {
  private static final Logger log = LoggerFactory.getLogger(AsyncOngoingRecording.class);

  private final OngoingRecording recording;
  private final Instant started = Instant.now();

  AsyncOngoingRecording(AsyncProfiler asyncProfiler, String recordingName)
      throws UnsupportedEnvironmentException {
    log.debug("Creating new recording: {}", recordingName);
    recording = asyncProfiler.start();
    if (recording == null) {
      throw new UnsupportedEnvironmentException("Failed to start async profiler");
    }
    log.debug("Recording {} started", recordingName);
  }

  @Override
  public RecordingData stop() {
    return recording.stop();
  }

  @Override
  public RecordingData snapshot(final Instant start) {
    return recording.snapshot(start);
  }

  @Override
  public void close() {
    recording.close();
  }
}
