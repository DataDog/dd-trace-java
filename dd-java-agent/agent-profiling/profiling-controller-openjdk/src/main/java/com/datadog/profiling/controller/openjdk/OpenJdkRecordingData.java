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

import datadog.trace.api.profiling.RecordingData;
import datadog.trace.api.profiling.RecordingInputStream;
import java.io.IOException;
import java.time.Instant;
import javax.annotation.Nonnull;
import jdk.jfr.Recording;

/** Implementation for profiling recordings. */
public class OpenJdkRecordingData extends RecordingData {

  private final Recording recording;

  OpenJdkRecordingData(final Recording recording, Kind kind) {
    this(recording, recording.getStartTime(), recording.getStopTime(), kind);
  }

  OpenJdkRecordingData(
      final Recording recording, final Instant start, final Instant end, Kind kind) {
    super(start, end, kind);
    this.recording = recording;
  }

  @Override
  @Nonnull
  public RecordingInputStream getStream() throws IOException {
    return new RecordingInputStream(recording.getStream(start, end));
  }

  @Override
  protected void doRelease() {
    recording.close();
  }

  @Override
  @Nonnull
  public String getName() {
    return recording.getName();
  }

  // Visible for testing
  Recording getRecording() {
    return recording;
  }
}
