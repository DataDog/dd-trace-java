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
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import com.datadoghq.profiling.controller.Controller;
import com.datadoghq.profiling.controller.RecordingData;

import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;

/**
 * This is the implementation of the controller for OpenJDK. It should work for JDK 11+ today, and
 * unmodified for JDK 8+ once JFR has been back-ported. The Oracle JDK implementation will be far
 * messier... ;)
 * 
 * @author Marcus Hirt
 */
public final class OpenJdkController implements Controller {

	@Override
	public RecordingData createRecording(String recordingName, Map<String, String> template, Duration duration)
			throws IOException {
		Recording recording = new Recording();
		recording.setName(recordingName);
		recording.setDuration(duration);
		recording.setSettings(template);
		recording.start();
		return new ProfilingRecording(recording);
	}

	@Override
	public RecordingData createContinuousRecording(String recordingName, Map<String, String> template) {
		Recording recording = new Recording();
		recording.setName(recordingName);
		recording.setSettings(template);
		recording.start();
		// probably limit maxSize to something sensible, and configurable, here. For now rely on in-memory.
		return new ContinuousRecording(recording);
	}

	@Override
	public RecordingData snapshot() throws IOException {
		return new ContinuousRecording(FlightRecorder.getFlightRecorder().takeSnapshot());
	}

	@Override
	public RecordingData snapshot(Instant start, Instant end) throws IOException {
		return new ContinuousRecording(FlightRecorder.getFlightRecorder().takeSnapshot(), start, end);
	}
}
