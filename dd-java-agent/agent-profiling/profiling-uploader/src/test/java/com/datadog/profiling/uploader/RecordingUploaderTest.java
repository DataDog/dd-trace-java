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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datadog.profiling.controller.RecordingData;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.io.ByteStreams;
import delight.fileupload.FileUpload;
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
import okhttp3.HttpUrl;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for the chunk uploader. */
public class RecordingUploaderTest {

  private static final String URL_PATH = "/v0.1/lalalala";
  private static final String APIKEY_VALUE = "testkey";
  private static final String RECORDING_1_CHUNK = "test-1-chunk.jfr";
  private static final String RECORDING_3_CHUNKS = "test-3-chunks.jfr";
  private static final int NUMBER_OF_CHUNKS = 3;
  private static final String RECODING_NAME_PREFIX = "test-recording-";

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
  private static final List<String> EXPECTED_TAGS = ImmutableList.of("baz:123", "foo:bar");

  private static final int SEQUENCE_NUMBER = 123;
  private static final int REQUESTED_START = 1000;
  private static final int REQUESTED_END = 1100;

  private final MockWebServer server = new MockWebServer();
  private HttpUrl url;

  private RecordingUploader uploader;

  @Before
  public void setup() throws IOException {
    server.start();
    url = server.url(URL_PATH);
    uploader = new RecordingUploader(url.toString(), APIKEY_VALUE, TAGS);
  }

  @After
  public void tearDown() throws IOException {
    try {
      server.shutdown();
    } catch (final IOException e) {
      // Looks like this happens for some unclear reason, but should not affect tests
    }
  }

  @Test
  public void testRequestParameters() throws IOException, InterruptedException {
    server.enqueue(new MockResponse().setResponseCode(200));

    uploader.upload(mockRecordingData(RECORDING_1_CHUNK));

    final RecordedRequest recordedRequest = server.takeRequest(5, TimeUnit.SECONDS);
    assertEquals(url, recordedRequest.getRequestUrl());

    final Multimap<String, Object> parameters = getParameters(recordedRequest);
    assertEquals(
        ImmutableList.of(RECODING_NAME_PREFIX + SEQUENCE_NUMBER),
        parameters.get(RecordingUploader.RECORDING_NAME_PARAM));
    assertEquals(
        ImmutableList.of(RecordingUploader.RECORDING_FORMAT),
        parameters.get(RecordingUploader.FORMAT_PARAM));
    assertEquals(
        ImmutableList.of(RecordingUploader.RECORDING_TYPE),
        parameters.get(RecordingUploader.TYPE_PARAM));
    assertEquals(
        ImmutableList.of(RecordingUploader.RECORDING_RUNTIME),
        parameters.get(RecordingUploader.RUNTIME_PARAM));

    assertEquals(
        ImmutableList.of(Instant.ofEpochSecond(REQUESTED_START).toString()),
        parameters.get(RecordingUploader.RECORDING_START_PARAM));
    assertEquals(
        ImmutableList.of(Instant.ofEpochSecond(REQUESTED_END).toString()),
        parameters.get(RecordingUploader.RECORDING_END_PARAM));

    assertEquals(
        ImmutableList.of(Integer.toString(0)),
        parameters.get(RecordingUploader.CHUNK_SEQUENCE_NUMBER_PARAM));

    assertEquals(
        EXPECTED_TAGS,
        parameters
            .get(RecordingUploader.TAGS_PARAM)
            .stream()
            .sorted()
            .collect(Collectors.toList()));

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
    uploader.upload(recording);

    verify(recording).release();
  }

  @Test
  public void test500Response() throws IOException, InterruptedException {
    server.enqueue(new MockResponse().setResponseCode(200));
    server.enqueue(new MockResponse().setResponseCode(500));
    server.enqueue(new MockResponse().setResponseCode(200));

    final RecordingData recording = mockRecordingData(RECORDING_3_CHUNKS);
    uploader.upload(recording);

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
    uploader.upload(recording);

    verify(recording).release();
  }

  @Test
  public void testTimeout() throws IOException, InterruptedException {
    server.enqueue(new MockResponse().setResponseCode(200));
    server.enqueue(
        new MockResponse()
            .setHeadersDelay(
                RecordingUploader.HTTP_TIMEOUT.plus(Duration.ofMillis(1000)).toMillis(),
                TimeUnit.MILLISECONDS));
    server.enqueue(new MockResponse().setResponseCode(200));

    final RecordingData recording = mockRecordingData(RECORDING_3_CHUNKS);
    uploader.upload(recording);

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
    uploader.upload(recording);

    verify(recording).release();
  }

  @Test
  public void testOnlyOneRequestFor1Chunk() throws IOException, InterruptedException {
    server.enqueue(new MockResponse().setResponseCode(200));
    server.enqueue(new MockResponse().setResponseCode(200));

    uploader.upload(mockRecordingData(RECORDING_1_CHUNK));

    assertNotNull("Expected chunk", server.takeRequest(5, TimeUnit.SECONDS));
    assertNull("No more requests", server.takeRequest(100, TimeUnit.MILLISECONDS));
  }

  @Test
  public void testRequestsFor3Chunks() throws IOException, InterruptedException {
    // One extra response to make sure we do not wait if we send extra request
    for (int i = 0; i < NUMBER_OF_CHUNKS + 1; i++) {
      server.enqueue(new MockResponse().setResponseCode(200));
    }

    final RecordingData recording = mockRecordingData(RECORDING_3_CHUNKS);
    uploader.upload(recording);

    final List<Integer> sequenceNumbers = new ArrayList<>();
    for (int i = 0; i < NUMBER_OF_CHUNKS; i++) {
      final RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
      assertNotNull("Expected chunk", request);

      final Multimap<String, Object> parameters = getParameters(request);

      // recordingNames.add(parameters.get(RecordingUploader.RECORDING_NAME_PARAM));
      assertEquals(
          ImmutableList.of(RECODING_NAME_PREFIX + SEQUENCE_NUMBER),
          parameters.get(RecordingUploader.RECORDING_NAME_PARAM));

      assertEquals(
          ImmutableList.of(Instant.ofEpochSecond(REQUESTED_START).toString()),
          parameters.get(RecordingUploader.RECORDING_START_PARAM));
      assertEquals(
          ImmutableList.of(Instant.ofEpochSecond(REQUESTED_END).toString()),
          parameters.get(RecordingUploader.RECORDING_END_PARAM));

      sequenceNumbers.addAll(
          parameters
              .get(RecordingUploader.CHUNK_SEQUENCE_NUMBER_PARAM)
              .stream()
              .map(o -> Integer.parseInt(o.toString()))
              .collect(Collectors.toList()));

      assertEquals(
          EXPECTED_TAGS,
          parameters
              .get(RecordingUploader.TAGS_PARAM)
              .stream()
              .sorted()
              .collect(Collectors.toList()));

      /*
      TODO: ideally we would like to check chunk data here as well. But chunk splitting code is
      not decoupled well enough to make this easy.
       */
    }

    sequenceNumbers.sort(Comparator.naturalOrder());
    assertEquals(
        "Got all chunks",
        IntStream.range(0, NUMBER_OF_CHUNKS).boxed().collect(Collectors.toList()),
        sequenceNumbers);

    assertNull("No more requests", server.takeRequest(100, TimeUnit.MILLISECONDS));

    verify(recording).release();
  }

  @Test
  public void testHeaders() throws IOException, InterruptedException {
    server.enqueue(new MockResponse().setResponseCode(200));

    uploader.upload(mockRecordingData(RECORDING_1_CHUNK));

    final RecordedRequest recordedRequest = server.takeRequest(5, TimeUnit.SECONDS);
    assertEquals(VersionInfo.JAVA_LANG, recordedRequest.getHeader(VersionInfo.DATADOG_META_LANG));
    assertEquals(
        VersionInfo.JAVA_VERSION, recordedRequest.getHeader(VersionInfo.DATADOG_META_LANG_VERSION));
    assertEquals(
        VersionInfo.JAVA_VM_NAME,
        recordedRequest.getHeader(VersionInfo.DATADOG_META_LANG_INTERPRETER));
    assertEquals(
        "Stubbed-Test-Version", recordedRequest.getHeader(VersionInfo.DATADOG_META_TRACER_VERSION));
  }

  @Test
  public void testTooManyRequests() throws IOException, InterruptedException {
    final int totalRequests =
        RecordingUploader.MAX_ENQUEUED_REQUESTS + RecordingUploader.MAX_RUNNING_REQUESTS;

    // We have to block all requests to make sure queue is kept full
    for (int i = 0; i < totalRequests; i++) {
      server.enqueue(
          new MockResponse()
              .setHeadersDelay(
                  RecordingUploader.HTTP_TIMEOUT.plus(Duration.ofMillis(1000)).toMillis(),
                  TimeUnit.MILLISECONDS));
    }
    server.enqueue(new MockResponse().setResponseCode(200));

    final List<RecordingData> recordings = new ArrayList<>();
    for (int i = 0; i < totalRequests; i++) {
      final RecordingData recording = mockRecordingData(RECORDING_1_CHUNK);
      uploader.upload(recording);
      recordings.add(recording);
    }

    final RecordingData additionalRecording = mockRecordingData(RECORDING_1_CHUNK);
    uploader.upload(additionalRecording);

    // Make sure all expected requests happened
    for (int i = 0; i < totalRequests; i++) {
      assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
    }
    // 'additionRecording' will not be added becasuse queue is full
    assertNull("No more requests", server.takeRequest(100, TimeUnit.MILLISECONDS));

    verify(additionalRecording).release();
  }

  @Test
  public void testShutdown() throws IOException, InterruptedException {
    uploader.shutdown();

    final RecordingData recording = mockRecordingData(RECORDING_1_CHUNK);
    uploader.upload(recording);

    assertNull("No more requests", server.takeRequest(100, TimeUnit.MILLISECONDS));

    verify(recording).release();
  }

  private Multimap<String, Object> getParameters(final RecordedRequest request) {
    return FileUpload.parse(request.getBody().readByteArray(), request.getHeader("Content-Type"))
        .stream()
        .collect(
            ImmutableMultimap::<String, Object>builder,
            (builder, value) ->
                builder.put(
                    value.getFieldName(),
                    RecordingUploader.OCTET_STREAM.toString().equals(value.getContentType())
                        ? value.get()
                        : value.getString()),
            (builder1, builder2) -> builder1.putAll(builder2.build()))
        .build();
  }

  private RecordingData mockRecordingData(final String recordingResource) throws IOException {
    final RecordingData recordingData = mock(RecordingData.class);
    when(recordingData.getStream())
        .thenReturn(
            Thread.currentThread().getContextClassLoader().getResourceAsStream(recordingResource));
    when(recordingData.getName()).thenReturn(RECODING_NAME_PREFIX + SEQUENCE_NUMBER);
    when(recordingData.getRequestedStart()).thenReturn(Instant.ofEpochSecond(REQUESTED_START));
    when(recordingData.getRequestedEnd()).thenReturn(Instant.ofEpochSecond(REQUESTED_END));
    return recordingData;
  }
}
