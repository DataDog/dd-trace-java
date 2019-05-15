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

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datadog.profiling.controller.RecordingData;
import com.datadog.profiling.controller.RecordingDataListener;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for the chunk uploader. */
public class ChunkUploaderTest {

  private static final String TEST_APIKEY_VALUE = "testkey";
  private static final String TEST_RECORDING = "test.jfr";
  private static final String RECODING_NAME_PREFIX = "test-recording-";

  private static final int NUMBER_OF_RECORDINGS = 3;

  private final MockWebServer server = new MockWebServer();
  private HttpUrl url;

  @Before
  public void setup() throws IOException {
    server.start();
    // TODO: check that url in request is correct
    url = server.url("/v0.1/lalalala");
  }

  @After
  public void tearDown() throws IOException {
    server.shutdown();
  }

  // TODO: add more tests for less than happy paths
  @Test
  public void testUploader() throws IOException, InterruptedException {
    for (int i = 0; i <= NUMBER_OF_RECORDINGS; i++) {
      server.enqueue(new MockResponse().setResponseCode(200));
    }

    // TODO: test with non empty tags
    final ChunkUploader uploader =
        new ChunkUploader(url.toString(), TEST_APIKEY_VALUE, Collections.emptyMap());

    final RecordingDataListener listener = uploader.getRecordingDataListener();
    for (int i = 0; i < NUMBER_OF_RECORDINGS; i++) {
      listener.onNewData(mockRecordingData(i));
    }

    final List<Map<String, String>> recordedRequests = new ArrayList<>();
    for (int i = 0; i < NUMBER_OF_RECORDINGS; i++) {
      final RecordedRequest recordedRequest = server.takeRequest(5, TimeUnit.SECONDS);
      if (recordedRequest == null) {
        break;
      } else {
        recordedRequests.add(getParameters(recordedRequest));
      }
    }

    uploader.shutdown();

    assertEquals(NUMBER_OF_RECORDINGS, recordedRequests.size());

    // Recording may get reordered so let sort them first
    recordedRequests.sort(Comparator.comparing(r -> r.get(UploadingTask.KEY_RECORDING_NAME)));
    for (int i = 0; i < NUMBER_OF_RECORDINGS; i++) {
      final Map<String, String> request = recordedRequests.get(i);
      assertEquals(
          "Recording name of " + i,
          RECODING_NAME_PREFIX + i,
          request.get(UploadingTask.KEY_RECORDING_NAME));
      // TODO: find recording with more than 1 chunk
      assertEquals(
          "Chunk sequence number of " + i,
          Integer.toString(0),
          request.get(UploadingTask.KEY_CHUNK_SEQ_NO));
    }
  }

  // TODO: replace this with the library
  private Map<String, String> getParameters(final RecordedRequest request) throws IOException {
    final Map<String, String> params = new HashMap<>();
    final String body = request.getBody().readUtf8();
    final BufferedReader reader = new BufferedReader(new StringReader(body));
    String line = null;
    while ((line = reader.readLine()) != null) {
      if (line.startsWith("Content-Disposition:")) {
        final int start = line.indexOf("name=") + 6;
        final int end = line.indexOf('"', start);
        final String key = line.substring(start, end);
        // Getting the first content line.
        for (int i = 0; i < 3; i++) {
          line = reader.readLine();
        }
        params.put(key, line);
      }
    }
    return params;
  }

  private RecordingData mockRecordingData(final int sequenceNumber) throws IOException {
    final RecordingData recordingData = mock(RecordingData.class);
    when(recordingData.getStream())
        .thenReturn(
            Thread.currentThread().getContextClassLoader().getResourceAsStream(TEST_RECORDING));
    when(recordingData.getName()).thenReturn(RECODING_NAME_PREFIX + sequenceNumber);
    when(recordingData.getRequestedStart()).thenReturn(Instant.ofEpochSecond(sequenceNumber));
    when(recordingData.getRequestedEnd()).thenReturn(Instant.ofEpochSecond(sequenceNumber + 100));
    return recordingData;
  }
}
