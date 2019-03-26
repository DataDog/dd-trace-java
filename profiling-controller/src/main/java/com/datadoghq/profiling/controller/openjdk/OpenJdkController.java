package com.datadoghq.profiling.controller.openjdk;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import com.datadoghq.profiling.controller.Controller;

import jdk.jfr.Recording;

/**
 * This is the implementation of the controller for OpenJDK. It should work for
 * JDK 11+ today, and unmodified for JDK 8+ once JFR has been backported.
 * 
 * The Oracle JDK implementation will be far messier... ;)
 * 
 * @author Marcus Hirt
 */
public final class OpenJdkController implements Controller {

	@Override
	public Recording createRecording(String recordingName, Map<String, String> template, Path destination, Duration duration) throws IOException {
		Recording recording = new Recording();
		recording.setName(recordingName);
		recording.setDuration(duration);
		recording.setDestination(destination);
		recording.setSettings(template);
		recording.start();
		return recording;
	}

	@Override
	public Recording createContinuousRecording(String recordingName, Map<String, String> template) {
		Recording recording = new Recording();
		recording.setName(recordingName);
		recording.setSettings(template);
		recording.start();
		// probably limit maxSize to something sensible, and configurable, here. For now rely on in-memory.
		return recording;
	}
}
