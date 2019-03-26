package com.datadoghq.profiling.controller;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import jdk.jfr.Recording;

/**
 * Interface for the low lever flight recorder control functionality. Needed since we will likely
 * want to support multiple version later.
 * 
 * @author Marcus Hirt
 */
public interface Controller {
	/**
	 * Starts a time limited recording using the specified template.
	 * 
	 * @param recordingName
	 *            the name under which the recording will be known.
	 * @param template
	 *            the event configurations which will be used when starting the continuous
	 *            recording.
	 * @param destination
	 *            the destination to which the recording will be written when completed.
	 * @param duration
	 *            the duration for which to record.
	 * @throws IOException
	 *             if something went wrong when scheduling the recording.
	 */
	Recording createRecording(String recordingName, Map<String, String> template, Path destination, Duration duration)
			throws IOException;

	/**
	 * Creates a continuous recording using the specified template.
	 * 
	 * @param recordingName
	 *            the name under which the recording will be known.
	 * @param template
	 *            the even configurations which will be used when starting the continuous recording.
	 * @return returns the recording object created.
	 */
	Recording createContinuousRecording(String recordingName, Map<String, String> template);

}
