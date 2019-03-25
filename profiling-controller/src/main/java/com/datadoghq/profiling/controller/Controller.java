package com.datadoghq.profiling.controller;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import jdk.jfr.Recording;

/**
 * Interface for the low lever flight recorder control functionality. Needed
 * since we will likely want to support multiple version later.
 * 
 * @author Marcus Hirt
 */
public interface Controller {
	/**
	 * Starts a time limited recording using the specified template.
	 * 
	 * @param recordingName
	 * @param templateLocation
	 * @param destination
	 * @param duration
	 * @throws IOException
	 */
	Recording createRecording(String recordingName, Map<String, String> template, Path destination, Duration duration)
			throws IOException;

}
