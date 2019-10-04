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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.datadog.profiling.controller.RecordingData;
import com.datadog.profiling.controller.RecordingType;
import com.datadog.profiling.testing.ProfilingTestUtils;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for the recording uploader. */
@ExtendWith(MockitoExtension.class)
public class RecordingUploaderTest {

  private static final String URL_PATH = "/v0.1/lalalala";
  private static final String APIKEY_VALUE = "testkey";
  private static final String RECORDING_1_CHUNK = "test-1-chunk.jfr";
  private static final String RECORDING_3_CHUNKS = "test-3-chunks.jfr";
  private static final int NUMBER_OF_CHUNKS = 3;
  private static final String RECODING_NAME_PREFIX = "test-recording-";
  private static final RecordingType RECORDING_TYPE = RecordingType.CONTINUOUS;

  private static final Map<String, String> TAGS;

  static {
    // Not using Guava's ImmutableMap because we want to test null value
    final Map<String, String> tags = new HashMap<>();
    tags.put("foo", "bar");
    tags.put("baz", "123");
    tags.put("null", null);
    tags.put("empty", "");
    TAGS = tags;
  }

  // We sort tags to have expected parameters to have expected result
  private static final Map<String, String> EXPECTED_TAGS =
      ImmutableMap.of("baz", "123", "foo", "bar");

  private static final int SEQUENCE_NUMBER = 123;
  private static final int RECORDING_START = 1000;
  private static final int RECORDING_END = 1100;

  // TODO: Add a test to verify overall request timeout rather than IO timeout
  private final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
  private final Duration REQUEST_IO_OPERATION_TIMEOUT = Duration.ofSeconds(5);

  private final MockWebServer server = new MockWebServer();
  private HttpUrl url;

  private RecordingUploader uploader;

  @BeforeEach
  public void setup() throws IOException {
    server.start();
    url = server.url(URL_PATH);

    uploader =
        new RecordingUploader(
            url.toString(), APIKEY_VALUE, TAGS, REQUEST_TIMEOUT, REQUEST_IO_OPERATION_TIMEOUT);
  }

  @AfterEach
  public void tearDown() throws IOException {
    uploader.shutdown();
    try {
      server.shutdown();
    } catch (final IOException e) {
      // Looks like this happens for some unclear reason, but should not affect tests
    }
  }

  @Test
  public void testRequestParameters() throws IOException, InterruptedException {
    server.enqueue(new MockResponse().setResponseCode(200));

    uploader.upload(RECORDING_TYPE, mockRecordingData(RECORDING_1_CHUNK));

    final RecordedRequest recordedRequest = server.takeRequest(5, TimeUnit.SECONDS);
    assertEquals(url, recordedRequest.getRequestUrl());

    assertEquals(Credentials.basic(APIKEY_VALUE, ""), recordedRequest.getHeader("Authorization"));

    final Multimap<String, Object> parameters =
        ProfilingTestUtils.parseProfilingRequestParameters(recordedRequest);
    assertEquals(
        ImmutableList.of(RECODING_NAME_PREFIX + SEQUENCE_NUMBER),
        parameters.get(RecordingUploader.RECORDING_NAME_PARAM));
    assertEquals(
        ImmutableList.of(RecordingUploader.RECORDING_FORMAT),
        parameters.get(RecordingUploader.FORMAT_PARAM));
    assertEquals(
        ImmutableList.of(RecordingUploader.RECORDING_TYPE_PREFIX + RECORDING_TYPE.getName()),
        parameters.get(RecordingUploader.TYPE_PARAM));
    assertEquals(
        ImmutableList.of(RecordingUploader.RECORDING_RUNTIME),
        parameters.get(RecordingUploader.RUNTIME_PARAM));

    assertEquals(
        ImmutableList.of(Instant.ofEpochSecond(RECORDING_START).toString()),
        parameters.get(RecordingUploader.RECORDING_START_PARAM));
    assertEquals(
        ImmutableList.of(Instant.ofEpochSecond(RECORDING_END).toString()),
        parameters.get(RecordingUploader.RECORDING_END_PARAM));

    assertEquals(
        ImmutableList.of(Integer.toString(0)),
        parameters.get(RecordingUploader.CHUNK_SEQUENCE_NUMBER_PARAM));

    assertEquals(
        EXPECTED_TAGS, ProfilingTestUtils.parseTags(parameters.get(RecordingUploader.TAGS_PARAM)));

    final byte[] expectedBytes =
        ByteStreams.toByteArray(
            Thread.currentThread().getContextClassLoader().getResourceAsStream(RECORDING_1_CHUNK));
    assertArrayEquals(
        expectedBytes,
        (byte[])
            Iterables.getFirst(parameters.get(RecordingUploader.CHUNK_DATA_PARAM), new byte[] {}));
  }

  @Test
  public void testRecordingClosed() throws IOException {
    server.enqueue(new MockResponse().setResponseCode(200));

    final RecordingData recording = mockRecordingData(RECORDING_1_CHUNK);
    uploader.upload(RECORDING_TYPE, recording);

    verify(recording).release();
  }

  @Test
  public void test500Response() throws IOException, InterruptedException {
    server.enqueue(new MockResponse().setResponseCode(200));
    server.enqueue(new MockResponse().setResponseCode(500));
    server.enqueue(new MockResponse().setResponseCode(200));

    final RecordingData recording = mockRecordingData(RECORDING_3_CHUNKS);
    uploader.upload(RECORDING_TYPE, recording);

    // We upload chunks in parallel so all three requests should happen
    assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
    assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
    assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));

    verify(recording).release();
  }

  @Test
  public void testConnectionRefused() throws IOException, InterruptedException {
    server.shutdown();

    final RecordingData recording = mockRecordingData(RECORDING_3_CHUNKS);
    uploader.upload(RECORDING_TYPE, recording);

    verify(recording).release();
  }

  @Test
  public void testTimeout() throws IOException, InterruptedException {
    server.enqueue(new MockResponse().setResponseCode(200));
    server.enqueue(
        new MockResponse()
            .setHeadersDelay(
                REQUEST_IO_OPERATION_TIMEOUT.plus(Duration.ofMillis(1000)).toMillis(),
                TimeUnit.MILLISECONDS));
    server.enqueue(new MockResponse().setResponseCode(200));

    final RecordingData recording = mockRecordingData(RECORDING_3_CHUNKS);
    uploader.upload(RECORDING_TYPE, recording);

    // We upload chunks in parallel so all three requests should happen
    assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
    assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
    assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));

    verify(recording).release();
  }

  @Test
  public void testUnfinishedRecording() throws IOException {
    final RecordingData recording = mockRecordingData(RECORDING_1_CHUNK);
    when(recording.getStream()).thenThrow(new IllegalStateException("test exception"));
    uploader.upload(RECORDING_TYPE, recording);

    verify(recording).release();
    verify(recording).getStream();
    verifyNoMoreInteractions(recording);
  }

  @Test
  public void testOnlyOneRequestFor1Chunk() throws IOException, InterruptedException {
    server.enqueue(new MockResponse().setResponseCode(200));
    server.enqueue(new MockResponse().setResponseCode(200));

    uploader.upload(RECORDING_TYPE, mockRecordingData(RECORDING_1_CHUNK));

    assertNotNull(server.takeRequest(5, TimeUnit.SECONDS), "Expected chunk");
    assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS), "No more requests");
  }

  @Test
  public void testRequestsFor3Chunks() throws IOException, InterruptedException {
    // One extra response to make sure we do not wait if we send extra request
    for (int i = 0; i < NUMBER_OF_CHUNKS + 1; i++) {
      server.enqueue(new MockResponse().setResponseCode(200));
    }

    final RecordingData recording = mockRecordingData(RECORDING_3_CHUNKS);
    uploader.upload(RECORDING_TYPE, recording);

    final List<Integer> sequenceNumbers = new ArrayList<>();
    for (int i = 0; i < NUMBER_OF_CHUNKS; i++) {
      final RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
      assertNotNull(request, "Expected chunk");

      final Multimap<String, Object> parameters =
          ProfilingTestUtils.parseProfilingRequestParameters(request);

      // recordingNames.add(parameters.get(RecordingUploader.RECORDING_NAME_PARAM));
      assertEquals(
          ImmutableList.of(RECODING_NAME_PREFIX + SEQUENCE_NUMBER),
          parameters.get(RecordingUploader.RECORDING_NAME_PARAM));

      assertEquals(
          ImmutableList.of(Instant.ofEpochSecond(RECORDING_START).toString()),
          parameters.get(RecordingUploader.RECORDING_START_PARAM));
      assertEquals(
          ImmutableList.of(Instant.ofEpochSecond(RECORDING_END).toString()),
          parameters.get(RecordingUploader.RECORDING_END_PARAM));

      sequenceNumbers.addAll(
          parameters
              .get(RecordingUploader.CHUNK_SEQUENCE_NUMBER_PARAM)
              .stream()
              .map(o -> Integer.parseInt(o.toString()))
              .collect(Collectors.toList()));

      assertEquals(
          EXPECTED_TAGS,
          ProfilingTestUtils.parseTags(parameters.get(RecordingUploader.TAGS_PARAM)));

      /*
      TODO: ideally we would like to check chunk data here as well. But chunk splitting code is
      not decoupled well enough to make this easy.
       */
    }

    sequenceNumbers.sort(Comparator.naturalOrder());
    assertEquals(
        IntStream.range(0, NUMBER_OF_CHUNKS).boxed().collect(Collectors.toList()),
        sequenceNumbers,
        "Got all chunks");

    assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS), "No more requests");

    verify(recording).release();
  }

  @Test
  public void testHeaders() throws IOException, InterruptedException {
    server.enqueue(new MockResponse().setResponseCode(200));

    uploader.upload(RECORDING_TYPE, mockRecordingData(RECORDING_1_CHUNK));

    final RecordedRequest recordedRequest = server.takeRequest(5, TimeUnit.SECONDS);
    assertEquals(VersionInfo.JAVA_LANG, recordedRequest.getHeader(VersionInfo.DATADOG_META_LANG));
    assertEquals(
        VersionInfo.JAVA_VERSION, recordedRequest.getHeader(VersionInfo.DATADOG_META_LANG_VERSION));
    assertEquals(
        VersionInfo.JAVA_VM_NAME,
        recordedRequest.getHeader(VersionInfo.DATADOG_META_LANG_INTERPRETER));
    assertEquals(
        VersionInfo.JAVA_VM_VENDOR,
        recordedRequest.getHeader(VersionInfo.DATADOG_META_LANG_INTERPRETER_VENDOR));
    assertEquals(
        "Stubbed-Test-Version", recordedRequest.getHeader(VersionInfo.DATADOG_META_TRACER_VERSION));
  }

  @Test
  public void testEnqueuedRequestsExecuted() throws IOException, InterruptedException {
    // We have to block all parallel requests to make sure queue is kept full
    for (int i = 0; i < RecordingUploader.MAX_RUNNING_REQUESTS; i++) {
      server.enqueue(
          new MockResponse()
              .setHeadersDelay(
                  // 1 second should be enough to schedule all requests and not hit timeout
                  Duration.ofMillis(1000).toMillis(), TimeUnit.MILLISECONDS)
              .setResponseCode(200));
    }
    server.enqueue(new MockResponse().setResponseCode(200));

    for (int i = 0; i < RecordingUploader.MAX_RUNNING_REQUESTS; i++) {
      final RecordingData recording = mockRecordingData(RECORDING_1_CHUNK);
      uploader.upload(RECORDING_TYPE, recording);
    }

    final RecordingData additionalRecording = mockRecordingData(RECORDING_1_CHUNK);
    uploader.upload(RECORDING_TYPE, additionalRecording);

    // Make sure all expected requests happened
    for (int i = 0; i < RecordingUploader.MAX_RUNNING_REQUESTS; i++) {
      assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
    }

    assertNotNull(server.takeRequest(2000, TimeUnit.MILLISECONDS), "Got enqueued request");

    verify(additionalRecording).release();
  }

  @Test
  public void testTooManyRequests() throws IOException, InterruptedException {
    // We have to block all parallel requests to make sure queue is kept full
    for (int i = 0; i < RecordingUploader.MAX_RUNNING_REQUESTS; i++) {
      server.enqueue(
          new MockResponse()
              .setHeadersDelay(
                  REQUEST_IO_OPERATION_TIMEOUT.plus(Duration.ofMillis(1000)).toMillis(),
                  TimeUnit.MILLISECONDS)
              .setResponseCode(200));
    }
    server.enqueue(new MockResponse().setResponseCode(200));

    for (int i = 0; i < RecordingUploader.MAX_RUNNING_REQUESTS; i++) {
      final RecordingData recording = mockRecordingData(RECORDING_1_CHUNK);
      uploader.upload(RECORDING_TYPE, recording);
    }

    final List<RecordingData> hangingRequests = new ArrayList<>();
    // We schedule one additional request to check case when request would be rejected immediately
    // rather than added to the queue.
    for (int i = 0; i < RecordingUploader.MAX_ENQUEUED_REQUESTS + 1; i++) {
      final RecordingData recording = mockRecordingData(RECORDING_1_CHUNK);
      hangingRequests.add(recording);
      uploader.upload(RECORDING_TYPE, recording);
    }

    // Make sure all expected requests happened
    for (int i = 0; i < RecordingUploader.MAX_RUNNING_REQUESTS; i++) {
      assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
    }
    // Recordings after RecordingUploader.MAX_RUNNING_REQUESTS will not be executed because number
    // or parallel requests has been reached.
    assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS), "No more requests");

    for (final RecordingData recording : hangingRequests) {
      verify(recording).release();
    }
  }

  @Test
  public void testShutdown() throws IOException, InterruptedException {
    uploader.shutdown();

    final RecordingData recording = mockRecordingData(RECORDING_1_CHUNK);
    uploader.upload(RECORDING_TYPE, recording);

    assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS), "No more requests");

    verify(recording).release();
  }

  private RecordingData mockRecordingData(final String recordingResource) throws IOException {
    final RecordingData recordingData = mock(RecordingData.class, withSettings().lenient());
    when(recordingData.getStream())
        .thenReturn(
            Thread.currentThread().getContextClassLoader().getResourceAsStream(recordingResource));
    when(recordingData.getName()).thenReturn(RECODING_NAME_PREFIX + SEQUENCE_NUMBER);
    when(recordingData.getStart()).thenReturn(Instant.ofEpochSecond(RECORDING_START));
    when(recordingData.getEnd()).thenReturn(Instant.ofEpochSecond(RECORDING_END));
    return recordingData;
  }
}
