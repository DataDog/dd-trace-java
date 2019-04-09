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
import java.io.InputStream;
import java.time.Instant;

/**
 * Platform agnostic API for operations required when retrieving data using the ProfilingSystem.
 * <p>
 * Note: Due to the use of {@link Instant}, this will require JDK 8 or above. We could switch this
 * and also support Oracle JDK 7 should we want to.
 * 
 * @See {@link ProfilingSystem}
 */
public interface RecordingData {
	/**
	 * True if the data stream is available, false if not.
	 * 
	 * @return
	 */
	boolean isAvailable();

	/**
	 * @return the data stream.
	 * @throws IllegalStateException
	 *             if the data is not available yet.
	 * @throws IOException
	 *             if another IO-related problem occured.
	 */
	InputStream getStream() throws IllegalStateException, IOException;

	/**
	 * For getting a stream (usually from a continuous recording).
	 * <p>
	 * A few things to note:
	 * <ul>
	 * <li>What data that will be possible to stream depends on the settings for the file repo, if
	 * present.</li>
	 * <li>More to come...</li>
	 * </ul>
	 * 
	 * @param start
	 *            the start time to try to get data for.
	 * @paratm end the end time to try to get data for.
	 * @return the data stream.
	 * @throws IllegalStateException
	 *             if the data is not available yet.
	 * @throws IOException
	 *             if another IO-related problem occurred.
	 */
	InputStream getStream(Instant start, Instant end) throws IllegalStateException, IOException;

	/**
	 * Releases the resources associated with the recording, for example the underlying file.
	 * <p>
	 * Forgetting to releasing this when done streaming, will need to one or more of the following:
	 * <ul>
	 * <li>Memory leak</li>
	 * <li>File leak</li>
	 * </ul>
	 * <p>
	 * Please don't forget to call release when done streaming...
	 */
	void release();

	/**
	 * Returns the name of the recording from which the data is originating.
	 * 
	 * @return the name of the recording from which the data is originating.
	 */
	String getName();
}
