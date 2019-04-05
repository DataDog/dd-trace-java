package com.datadoghq.profiling.uploader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.datadoghq.profiling.controller.ProfilingSystem;
import com.datadoghq.profiling.controller.UnsupportedEnvironmentException;
import com.squareup.okhttp.HttpUrl;
import com.squareup.okhttp.mockwebserver.Dispatcher;
import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.MockWebServer;
import com.squareup.okhttp.mockwebserver.RecordedRequest;

/**
 * Unit test for simple App.
 */
public class ChunkUploaderTest {
	private static final String TEST_APIKEY_VALUE = "testkey";
	private static final int NUMBER_OF_RECORDINGS = 3;

	@Test
	public void testUploader() throws IOException, UnsupportedEnvironmentException, InterruptedException {
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
		ChunkUploader uploader = new ChunkUploader(url.toString(), TEST_APIKEY_VALUE);

		ProfilingSystem system = new ProfilingSystem(uploader.getRecordingDataListener(), Duration.ZERO,
				Duration.ofSeconds(5), Duration.ofSeconds(2));
		system.start();

		latch.await(45, TimeUnit.SECONDS);
		system.shutdown();
		uploader.shutdown();

		assertEquals("Got the right amount of recordings tests ", NUMBER_OF_RECORDINGS, recordedRequests.size());

		for (RecordedRequest requests : recordedRequests) {
			requests.getHeader(UploadingTask.HEADER_KEY_JFRCHUNKID);
			assertEquals("Not the right API key!", TEST_APIKEY_VALUE,
					requests.getHeader(UploadingTask.HEADER_KEY_APIKEY));
			assertTrue("Expected a profiling dump name",
					requests.getHeader(UploadingTask.HEADER_KEY_JFRNAME).startsWith("dd-profiling-"));
			int chunkId = Integer.valueOf(requests.getHeader(UploadingTask.HEADER_KEY_JFRCHUNKID));
			assertTrue("Expected a chunk id larger or equal to zero", chunkId >= 0);
		}

		server.shutdown();
	}

	public static void main(String[] args) throws IOException, UnsupportedEnvironmentException, InterruptedException {
		new ChunkUploaderTest().testUploader();
	}
}
