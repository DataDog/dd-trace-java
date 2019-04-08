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
package com.datadoghq.profiling.controller.openjdk;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;

import com.datadoghq.profiling.controller.RecordingData;

import jdk.jfr.Recording;
import jdk.jfr.RecordingState;

/**
 * FIXME: This can probably be joined with {@link ContinuousRecording} after the latest refactoring.
 */
public class ProfilingRecording implements RecordingData {
	private final Recording recording;

	public ProfilingRecording(Recording recording) {
		this.recording = recording;
	}

	@Override
	public boolean isAvailable() {
		return recording.getState() == RecordingState.STOPPED;
	}

	@Override
	public InputStream getStream() throws IllegalStateException, IOException {
		if (!isAvailable()) {
			throw new IllegalStateException(
					"Can't get stream from a profiling recording until the recording is finished!");
		}
		return recording.getStream(null, null);
	}

	@Override
	public void release() {
		recording.close();
	}

	@Override
	public InputStream getStream(Instant start, Instant end) throws IllegalStateException, IOException {
		// Might come in handy for long lasting profiling recordings not quite done yet, but we may want to not allow.
		return recording.getStream(start, end);
	}

	@Override
	public String getName() {
		return recording.getName();
	}

	@Override
	public String toString() {
		return "ProfilingRecording: " + getName();
	}
}
