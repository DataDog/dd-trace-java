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
import static datadog.trace.api.config.ProfilingConfig.PROFILING_OTLP_MODE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import datadog.trace.api.Config;
import datadog.trace.api.config.OtlpConfig;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.api.profiling.ProfilingSnapshot;
import datadog.trace.api.profiling.RecordingData;
import datadog.trace.api.profiling.RecordingInputStream;
import datadog.trace.api.profiling.RecordingType;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;

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

    when(config.getFinalProfilingUrl()).thenReturn("http://localhost:8126");
    when(config.getProfilingUploadTimeout()).thenReturn((int) REQUEST_TIMEOUT.getSeconds());
    when(config.getProfilingProxyHost()).thenReturn(null);
    when(config.getProfilingProxyPort()).thenReturn(8080);
    when(config.getProfilingProxyUsername()).thenReturn(null);
    when(config.getProfilingProxyPassword()).thenReturn(null);

    when(config.getServiceName()).thenReturn("test-service");
    when(config.getEnv()).thenReturn("");
    when(config.getVersion()).thenReturn("");
    when(config.isReportHostName()).thenReturn(false);

    when(config.getOtlpProfilesProtocol()).thenReturn(OtlpConfig.Protocol.HTTP_PROTOBUF);
    when(config.getOtlpProfilesEndpoint()).thenReturn(otlpUrl);
    when(config.getOtlpProfilesHeaders()).thenReturn(Collections.emptyMap());
    when(config.getOtlpProfilesTimeout()).thenReturn((int) REQUEST_TIMEOUT.getSeconds());
    when(config.getOtlpProfilesCompression()).thenReturn(OtlpConfig.Compression.NONE);

    when(configProvider.getBoolean(PROFILING_OTLP_ENABLED, false)).thenReturn(true);
    when(configProvider.getEnum(
            PROFILING_OTLP_MODE, ProfilingConfig.OtlpMode.class, ProfilingConfig.OtlpMode.LIGHT))
        .thenReturn(ProfilingConfig.OtlpMode.LIGHT);

    uploader =
        new OtlpProfileUploader(config, configProvider, (int) TERMINATION_TIMEOUT.getSeconds());
  }

  @AfterEach
  public void teardown() throws IOException {
    uploader.shutdown();
    server.shutdown();
  }

  @Test
  public void testDisabledUploader() throws Exception {
    when(configProvider.getBoolean(PROFILING_OTLP_ENABLED, false)).thenReturn(false);

    OtlpProfileUploader disabledUploader =
        new OtlpProfileUploader(config, configProvider, (int) TERMINATION_TIMEOUT.getSeconds());

    RecordingData data = mockRecordingData();

    disabledUploader.onNewData(RECORDING_TYPE, data, true);

    assertEquals(0, server.getRequestCount());
    verify(data).release();

    disabledUploader.shutdown();
  }

  @Test
  public void testConfigurationReading() throws Exception {
    assertTrue(uploader != null);
  }

  @Test
  public void testLightweightUploadCarriesResourceAttributes() throws Exception {
    server.enqueue(new MockResponse());

    RecordingData data = mockRecordingData();

    uploader.onNewData(RECORDING_TYPE, data, true);

    assertEquals(1, server.getRequestCount());
    RecordedRequest request = server.takeRequest(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    assertNotNull(request);
    byte[] body = request.getBody().readByteArray();
    // String fields are encoded inline on the wire, so the attribute key/value pairs are
    // searchable in the raw payload
    assertTrue(new String(body, StandardCharsets.ISO_8859_1).contains("service.name"));
    assertTrue(new String(body, StandardCharsets.ISO_8859_1).contains("test-service"));
    assertTrue(new String(body, StandardCharsets.ISO_8859_1).contains("telemetry.sdk.name"));
    verify(data).release();
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

  private byte[] decompress(byte[] compressed) throws IOException {
    try (GZIPInputStream gzipIn = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
      byte[] buffer = new byte[compressed.length * 10]; // Assume max 10x expansion
      int bytesRead = gzipIn.read(buffer);
      byte[] result = new byte[bytesRead];
      System.arraycopy(buffer, 0, result, 0, bytesRead);
      return result;
    }
  }
}
