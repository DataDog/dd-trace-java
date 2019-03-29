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
package com.datadoghq.profiling.controller;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

/**
 * Interface for the low lever flight recorder control functionality. Needed since we will likely
 * want to support multiple version later.
 * 
 * @author Marcus Hirt
 */
public interface Controller {
	/**
	 * Starts a time limited recording using the specified template. Note that the data will not be
	 * available until the recording is done, and some other mechanism will need to be put in place
	 * for that to completed and determined.
	 * 
	 * @param recordingName
	 *            the name under which the recording will be known.
	 * @param template
	 *            the event configurations which will be used when starting the continuous
	 *            recording.
	 * @param duration
	 *            the duration for which to record.
	 * @throws IOException
	 *             if something went wrong when scheduling the recording.
	 */
	RecordingData createRecording(String recordingName, Map<String, String> template, Duration duration)
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
	RecordingData createContinuousRecording(String recordingName, Map<String, String> template);

	/**
	 * Will snapshot the flight recorder, giving stable access to, for example, later stream between
	 * two different times.
	 * 
	 * @throws IOException
	 *             if something went wrong taking the snapshot.
	 */
	public RecordingData snapshot() throws IOException;

}
