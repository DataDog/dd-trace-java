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
 * @author Marcus Hirt
 */
public class OpenJdkController implements Controller {

	/**
	 * Starts a time limited recording using the specified template.
	 * 
	 * @param recordingName
	 * @param template
	 * @param destination
	 * @param duration
	 * @throws IOException
	 */
	public Recording createRecording(String recordingName, Map<String, String> template, Path destination, Duration duration) throws IOException {
		Recording recording = new Recording();
		recording.setName(recordingName);
		recording.setDuration(duration);
		recording.setDestination(destination);
		recording.setSettings(template);
		recording.start();
		return recording;
	}
}
