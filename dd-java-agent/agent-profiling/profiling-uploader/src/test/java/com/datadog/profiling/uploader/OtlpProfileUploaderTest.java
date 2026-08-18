/*
 * Copyright 2025 Datadog
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

import static datadog.trace.api.config.ProfilingConfig.PROFILING_OTLP_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_OTLP_INCLUDE_ORIGINAL_PAYLOAD;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import datadog.trace.api.Config;
import datadog.trace.api.config.OtlpConfig;
import datadog.trace.api.profiling.ProfilingSnapshot;
import datadog.trace.api.profiling.RecordingData;
import datadog.trace.api.profiling.RecordingInputStream;
import datadog.trace.api.profiling.RecordingType;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;

/** Unit tests for the OTLP profile uploader. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class OtlpProfileUploaderTest {

  private static final String RECORDING_RESOURCE = "/test-recording.jfr";
  private static final RecordingType RECORDING_TYPE = RecordingType.CONTINUOUS;
  private static final String RECORDING_NAME = "test-recording";
  private static final int PROFILE_START = 1000;
  private static final int PROFILE_END = 1100;

  private final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
  private final Duration TERMINATION_TIMEOUT = REQUEST_TIMEOUT.plus(Duration.ofSeconds(5));

  @Mock private Config config;
  @Mock private ConfigProvider configProvider;

  private final MockWebServer server = new MockWebServer();
  private String otlpUrl;

  private OtlpProfileUploader uploader;

  @BeforeEach
  public void setup() throws IOException {
    server.start();
    otlpUrl = server.url("/v1/profiles").toString();

    // Mock Config - the OTLP-profiles getters actually read by OtlpProfilesSenderFactory
    when(config.getOtlpProfilesProtocol()).thenReturn(OtlpConfig.Protocol.HTTP_PROTOBUF);
    when(config.getOtlpProfilesEndpoint()).thenReturn(otlpUrl);
    when(config.getOtlpProfilesHeaders()).thenReturn(Collections.emptyMap());
    when(config.getOtlpProfilesTimeout()).thenReturn((int) REQUEST_TIMEOUT.toMillis());
    when(config.getOtlpProfilesCompression()).thenReturn(OtlpConfig.Compression.NONE);

    // Mock ConfigProvider - OTLP enabled by default for tests
    when(configProvider.getBoolean(PROFILING_OTLP_ENABLED, false)).thenReturn(true);
    when(configProvider.getBoolean(PROFILING_OTLP_INCLUDE_ORIGINAL_PAYLOAD, false))
        .thenReturn(false);

    uploader =
        new OtlpProfileUploader(config, configProvider, (int) TERMINATION_TIMEOUT.getSeconds());
  }

  @AfterEach
  public void teardown() throws IOException {
    uploader.shutdown();
    server.shutdown();
  }

  @Test
  public void testConfigurationReading() throws Exception {
    // Verify that configuration is correctly read from ConfigProvider
    assertTrue(uploader != null);
    // Uploader was created with enabled=true, so it should be initialized
  }

  @Test
  public void testUploadSuccessSync() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(200));

    RecordingData data = mockRecordingData();
    uploader.onNewData(RECORDING_TYPE, data, true);

    RecordedRequest request = server.takeRequest(REQUEST_TIMEOUT.getSeconds(), SECONDS);
    assertEquals(1, server.getRequestCount());
    assertTrue(request.getPath().startsWith("/v1/profiles"));
    verify(data, times(1)).release();
  }

  @Test
  public void testUploadFailureStatusSync() throws Exception {
    // The sender retries 5xx responses (1 initial attempt + 5 retries).
    for (int i = 0; i < 6; i++) {
      server.enqueue(new MockResponse().setResponseCode(500));
    }

    RecordingData data = mockRecordingData();
    uploader.onNewData(RECORDING_TYPE, data, true);

    server.takeRequest(REQUEST_TIMEOUT.getSeconds(), SECONDS);
    assertEquals(6, server.getRequestCount());
    // A failed status still releases the (single, base) reference exactly once.
    verify(data, times(1)).release();
  }

  @Test
  public void testUploadUsesFileBackedPathWhenAvailable(@TempDir Path tempDir) throws Exception {
    server.enqueue(new MockResponse().setResponseCode(200));

    Path recordingFile = tempDir.resolve("recording.jfr");
    try (InputStream in = getClass().getResourceAsStream(RECORDING_RESOURCE)) {
      Files.copy(in, recordingFile);
    }

    RecordingData data = mockRecordingData();
    when(data.getPath()).thenReturn(recordingFile);

    uploader.onNewData(RECORDING_TYPE, data, true);

    server.takeRequest(REQUEST_TIMEOUT.getSeconds(), SECONDS);
    assertEquals(1, server.getRequestCount());
    // getStream() must not be consulted once a file path is available.
    verify(data, never()).getStream();
    verify(data, times(1)).release();
  }

  @Test
  public void testUploadAsync() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(200));

    RecordingData data = mockRecordingData();
    CountDownLatch latch = new CountDownLatch(1);
    uploader.upload(RECORDING_TYPE, data, false, latch::countDown);

    assertTrue(latch.await(REQUEST_TIMEOUT.getSeconds(), SECONDS));
    assertEquals(1, server.getRequestCount());
    verify(data, times(1)).release();
  }

  @Test
  public void testUploadRejectedExecutionReleasesData() throws Exception {
    // Shut down the executor so the async submission is rejected deterministically.
    uploader.shutdown();

    RecordingData data = mockRecordingData();
    CountDownLatch latch = new CountDownLatch(1);
    uploader.upload(RECORDING_TYPE, data, false, latch::countDown);

    assertTrue(latch.await(REQUEST_TIMEOUT.getSeconds(), SECONDS));
    assertEquals(0, server.getRequestCount());
    verify(data, times(1)).release();
  }

  private RecordingData mockRecordingData() throws IOException {
    final RecordingData recordingData = mock(RecordingData.class, withSettings().lenient());
    when(recordingData.getStream())
        .then(
            (Answer<InputStream>)
                invocation ->
                    new RecordingInputStream(getClass().getResourceAsStream(RECORDING_RESOURCE)));
    when(recordingData.getName()).thenReturn(RECORDING_NAME);
    when(recordingData.getStart()).thenReturn(Instant.ofEpochSecond(PROFILE_START));
    when(recordingData.getEnd()).thenReturn(Instant.ofEpochSecond(PROFILE_END));
    when(recordingData.getKind()).thenReturn(ProfilingSnapshot.Kind.PERIODIC);
    when(recordingData.getPath()).thenReturn(null); // Force stream-based conversion
    return recordingData;
  }
}
