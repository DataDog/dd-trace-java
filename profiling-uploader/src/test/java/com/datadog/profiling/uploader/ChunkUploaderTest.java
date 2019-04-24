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
package com.datadog.profiling.uploader;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.datadog.profiling.controller.ProfilingSystem;
import com.squareup.okhttp.Credentials;
import com.squareup.okhttp.HttpUrl;
import com.squareup.okhttp.mockwebserver.Dispatcher;
import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.MockWebServer;
import com.squareup.okhttp.mockwebserver.RecordedRequest;

/**
 * Unit tests for the chunk uploader.
 */
public class ChunkUploaderTest {
	private static final String TEST_APIKEY_VALUE = "testkey";
	private static final int NUMBER_OF_RECORDINGS = 3;

	@Test
	public void testUploader() throws Exception {
		final List<RecordedRequest> recordedRequests = Collections.synchronizedList(new ArrayList<RecordedRequest>());
		final CountDownLatch latch = new CountDownLatch(NUMBER_OF_RECORDINGS);

		MockWebServer server = new MockWebServer();
		server.setDispatcher(new Dispatcher() {
			@Override
			public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
				recordedRequests.add(request);
				latch.countDown();
				return new MockResponse().setResponseCode(200);
			}
		});
		server.start();
		HttpUrl url = server.url("/v0.1/lalalala");
		ChunkUploader uploader = new ChunkUploader(url.toString(), TEST_APIKEY_VALUE, Credentials.basic("user", "pwd"));

		ProfilingSystem system = new ProfilingSystem(uploader.getRecordingDataListener(), Duration.ZERO,
				Duration.ofMillis(10), Duration.ofMillis(10));
		system.start();

		latch.await(15, TimeUnit.SECONDS);
		system.shutdown();
		uploader.shutdown();

		assertThat("Didn't get the right amount of recordings", recordedRequests.size(), greaterThanOrEqualTo(NUMBER_OF_RECORDINGS));

		for (RecordedRequest request : recordedRequests) {
			Map<String, String> params = getParameters(request);
			assertEquals("Not the right API key!", TEST_APIKEY_VALUE,
					request.getHeader(UploadingTask.HEADER_KEY_APIKEY));
			assertTrue("Expected a profiling dump name",
					params.get(UploadingTask.KEY_RECORDING_NAME).startsWith("dd-profiling-"));
			int chunkId = Integer.valueOf(params.get(UploadingTask.KEY_CHUNK_SEQ_NO));
			assertTrue("Expected a chunk id larger or equal to zero", chunkId >= 0);
		}

		server.shutdown();
	}

	private Map<String, String> getParameters(RecordedRequest request) throws IOException {
		Map<String, String> params = new HashMap<String, String>();
		String body = request.getBody().readUtf8();
		BufferedReader reader = new BufferedReader(new StringReader(body));
		String line = null;
		while ((line = reader.readLine()) != null) {
			if (line.startsWith("Content-Disposition:")) {
				int start = line.indexOf("name=") + 6;
				int end = line.indexOf('"', start);
				String key = line.substring(start, end);
				// Getting the first content line.
				for (int i = 0; i < 3; i++) {
					line = reader.readLine();
				}
				params.put(key, line);
			}
		}
		return params;
	}

	public static void main(String[] args) throws Exception {
		new ChunkUploaderTest().testUploader();
	}
}
