package com.datadoghq.profiling.controller.openjdk;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;

import com.datadoghq.profiling.controller.RecordingData;

import jdk.jfr.Recording;
import jdk.jfr.RecordingState;

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
