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

import static com.datadog.profiling.uploader.ProfileUploader.SERVELESS_TAG;
import static com.datadog.profiling.uploader.ProfileUploader.V4_PROFILE_END_PARAM;
import static com.datadog.profiling.uploader.ProfileUploader.V4_PROFILE_START_PARAM;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.datadog.profiling.testing.ProfilingTestUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import datadog.common.version.VersionInfo;
import datadog.environment.JavaVirtualMachine;
import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.ProcessTags;
import datadog.trace.api.profiling.ProfilingSnapshot;
import datadog.trace.api.profiling.RecordingData;
import datadog.trace.api.profiling.RecordingInputStream;
import datadog.trace.api.profiling.RecordingType;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.relocate.api.IOLogger;
import datadog.trace.test.util.ControllableEnvironmentVariables;
import datadog.trace.util.PidHelper;
import delight.fileupload.FileUpload;
import io.airlift.compress.zstd.ZstdInputStream;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.ConnectException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.jpountz.lz4.LZ4FrameInputStream;
import okhttp3.ConnectionSpec;
import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.internal.verification.VerificationModeFactory;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

/** Unit tests for the recording uploader. */
@ExtendWith(MockitoExtension.class)
public class ProfileUploaderTest {

  private static final String API_KEY_VALUE = "testkey";
  private static final String URL_PATH = "/lalala";
  private static final String RECORDING_RESOURCE = "/test-recording.jfr";
  private static final String RECORDING_NAME_PREFIX = "test-recording-";
  private static final RecordingType RECORDING_TYPE = RecordingType.CONTINUOUS;

  private static final Map<String, String> TAGS;

  private static final String FUNCTION_NAME = "my_function";

  static {
    // Not using Guava's ImmutableMap because we want to test null value
    final Map<String, String> tags = new HashMap<>();
    tags.put("foo", "bar");
    tags.put("baz", "123");
    tags.put("null", null);
    tags.put("empty", "");
    tags.put("quoted", "\"quoted\"");
    TAGS = tags;
  }

  // We sort tags to have expected parameters to have expected result
  private static final Map<String, String> EXPECTED_TAGS =
      new ImmutableMap.Builder<String, String>()
          .put("baz", "123")
          .put("foo", "bar")
          .put(SERVELESS_TAG, FUNCTION_NAME)
          .put("quoted", "quoted")
          .put(DDTags.PID_TAG, PidHelper.getPid())
          .put(VersionInfo.PROFILER_VERSION_TAG, VersionInfo.VERSION)
          .put(VersionInfo.LIBRARY_VERSION_TAG, VersionInfo.VERSION)
          .build();

  private static final int SEQUENCE_NUMBER = 123;
  private static final int PROFILE_START = 1000;
  private static final int PROFILE_END = 1100;

  // TODO: Add a test to verify overall request timeout rather than IO timeout
  private final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
  private final Duration REQUEST_IO_OPERATION_TIMEOUT = Duration.ofSeconds(5);

  // Termination timeout has to be longer than request timeout to make sure that all callbacks are
  // called before the termination.
  private final Duration TERMINATION_TIMEOUT = REQUEST_TIMEOUT.plus(Duration.ofSeconds(5));

  private final Duration FOREVER_REQUEST_TIMEOUT = Duration.ofSeconds(1000);

  @Mock private Config config;
  @Mock private ConfigProvider configProvider;
  @Mock private IOLogger ioLogger;

  private final MockWebServer server = new MockWebServer();
  private HttpUrl url;

  private ProfileUploader uploader;

  @SuppressWarnings("unused")
  private static final ControllableEnvironmentVariables ENV =
      ControllableEnvironmentVariables.setup("AWS_LAMBDA_FUNCTION_NAME", FUNCTION_NAME);

  @BeforeEach
  public void setup() throws IOException {
    server.start();
    url = server.url(URL_PATH);

    when(config.getFinalProfilingUrl()).thenReturn(server.url(URL_PATH).toString());
    when(config.isProfilingAgentless()).thenReturn(false);
    when(config.getMergedProfilingTags()).thenReturn(TAGS);
    when(config.getProfilingUploadTimeout()).thenReturn((int) REQUEST_TIMEOUT.getSeconds());
    when(config.isProfilingUploadSummaryOn413Enabled()).thenReturn(true);

    uploader =
        new ProfileUploader(
            config, configProvider, ioLogger, (int) TERMINATION_TIMEOUT.getSeconds());
  }

  @AfterEach
  public void tearDown() {
    uploader.shutdown();
    try {
      server.shutdown();
    } catch (final IOException e) {
      // Looks like this happens for some unclear reason, but should not affect tests
    }
  }

  @ParameterizedTest
  @EnumSource(ProfilingSnapshot.Kind.class)
  public void testHappyPath(ProfilingSnapshot.Kind kind) throws Exception {
    // Given
    when(config.getProfilingUploadTimeout()).thenReturn(500000);

    // When
    uploader = new ProfileUploader(config, configProvider);
    server.enqueue(new MockResponse().setResponseCode(200));
    uploadAndWait(RECORDING_TYPE, mockRecordingData(true, kind));
    final RecordedRequest recordedRequest = server.takeRequest(5, TimeUnit.SECONDS);

    // Then
    assertEquals(url, recordedRequest.getRequestUrl());

    final List<FileItem> multiPartItems =
        FileUpload.parse(
            recordedRequest.getBody().readByteArray(), recordedRequest.getHeader("Content-Type"));

    final FileItem rawEvent = multiPartItems.get(0);
    assertEquals(ProfileUploader.V4_EVENT_NAME, rawEvent.getFieldName());
    assertEquals(ProfileUploader.V4_EVENT_FILENAME, rawEvent.getName());
    assertEquals("application/json", rawEvent.getContentType());

    final FileItem rawJfr = multiPartItems.get(1);
    assertEquals(ProfileUploader.V4_ATTACHMENT_NAME, rawJfr.getFieldName());
    assertEquals(ProfileUploader.V4_ATTACHMENT_FILENAME, rawJfr.getName());
    assertEquals("application/octet-stream", rawJfr.getContentType());

    final byte[] expectedBytes = ByteStreams.toByteArray(recordingStream(true));
    assertArrayEquals(expectedBytes, rawJfr.get());

    // Event checks
    final ObjectMapper mapper = new ObjectMapper();
    final JsonNode event = mapper.readTree(rawEvent.getString());

    assertEquals(ProfileUploader.V4_ATTACHMENT_FILENAME, event.get("attachments").get(0).asText());
    assertEquals(ProfileUploader.V4_FAMILY, event.get("family").asText());
    assertEquals(ProfileUploader.V4_VERSION, event.get("version").asText());
    assertEquals(
        Instant.ofEpochSecond(PROFILE_START).toString(),
        event.get(V4_PROFILE_START_PARAM).asText());
    assertEquals(
        Instant.ofEpochSecond(PROFILE_END).toString(), event.get(V4_PROFILE_END_PARAM).asText());
    Map<String, String> expectedTags = new TreeMap<>(EXPECTED_TAGS);
    expectedTags.put("snapshot", kind.name().toLowerCase());
    assertEquals(
        expectedTags,
        ProfilingTestUtils.parseTags(
            Arrays.asList(event.get("tags_profiler").asText().split(","))));
  }

  @ParameterizedTest
  @EnumSource(ProfilingSnapshot.Kind.class)
  public void testHappyPathSync(ProfilingSnapshot.Kind kind) throws Exception {
    // Given
    when(config.getProfilingUploadTimeout()).thenReturn(500000);

    // When
    uploader = new ProfileUploader(config, configProvider);
    server.enqueue(new MockResponse().setResponseCode(200));
    // upload synchronously
    uploader.upload(RECORDING_TYPE, mockRecordingData(true, kind), true);
    final RecordedRequest recordedRequest = server.takeRequest(5, TimeUnit.SECONDS);

    // Then
    assertEquals(url, recordedRequest.getRequestUrl());

    final List<FileItem> multiPartItems =
        FileUpload.parse(
            recordedRequest.getBody().readByteArray(), recordedRequest.getHeader("Content-Type"));

    final FileItem rawEvent = multiPartItems.get(0);
    assertEquals(ProfileUploader.V4_EVENT_NAME, rawEvent.getFieldName());
    assertEquals(ProfileUploader.V4_EVENT_FILENAME, rawEvent.getName());
    assertEquals("application/json", rawEvent.getContentType());

    final FileItem rawJfr = multiPartItems.get(1);
    assertEquals(ProfileUploader.V4_ATTACHMENT_NAME, rawJfr.getFieldName());
    assertEquals(ProfileUploader.V4_ATTACHMENT_FILENAME, rawJfr.getName());
    assertEquals("application/octet-stream", rawJfr.getContentType());

    final byte[] expectedBytes = ByteStreams.toByteArray(recordingStream(true));
    assertArrayEquals(expectedBytes, rawJfr.get());

    // Event checks
    final ObjectMapper mapper = new ObjectMapper();
    final JsonNode event = mapper.readTree(rawEvent.getString());

    assertEquals(ProfileUploader.V4_ATTACHMENT_FILENAME, event.get("attachments").get(0).asText());
    assertEquals(ProfileUploader.V4_FAMILY, event.get("family").asText());
    assertEquals(ProfileUploader.V4_VERSION, event.get("version").asText());
    assertEquals(
        Instant.ofEpochSecond(PROFILE_START).toString(),
        event.get(V4_PROFILE_START_PARAM).asText());
    assertEquals(
        Instant.ofEpochSecond(PROFILE_END).toString(), event.get(V4_PROFILE_END_PARAM).asText());
    Map<String, String> expectedTags = new TreeMap<>(EXPECTED_TAGS);
    expectedTags.put("snapshot", kind.name().toLowerCase());
    assertEquals(
        expectedTags,
        ProfilingTestUtils.parseTags(
            Arrays.asList(event.get("tags_profiler").asText().split(","))));
  }

  @Test
  public void testZippedInput() throws Exception {
    when(config.getApiKey()).thenReturn(null);
    when(config.getProfilingUploadCompression()).thenReturn("on");
    when(config.getProfilingUploadTimeout()).thenReturn(500000);
    uploader = new ProfileUploader(config, configProvider);

    server.enqueue(new MockResponse().setResponseCode(200));

    uploadAndWait(RECORDING_TYPE, mockRecordingData(true));

    final RecordedRequest recordedRequest = server.takeRequest(5, TimeUnit.SECONDS);
    assertEquals(url, recordedRequest.getRequestUrl());

    assertNull(recordedRequest.getHeader("DD-API-KEY"));

    final List<FileItem> multiPartItems =
        FileUpload.parse(
            recordedRequest.getBody().readByteArray(), recordedRequest.getHeader("Content-Type"));

    final FileItem rawEvent = multiPartItems.get(0);
    assertEquals(ProfileUploader.V4_EVENT_NAME, rawEvent.getFieldName());
    assertEquals(ProfileUploader.V4_EVENT_FILENAME, rawEvent.getName());
    assertEquals("application/json", rawEvent.getContentType());

    final FileItem rawJfr = multiPartItems.get(1);
    assertEquals(ProfileUploader.V4_ATTACHMENT_NAME, rawJfr.getFieldName());
    assertEquals(ProfileUploader.V4_ATTACHMENT_FILENAME, rawJfr.getName());
    assertEquals("application/octet-stream", rawJfr.getContentType());

    // data which are originally zipped will not be recompressed
    final byte[] expectedBytes = ByteStreams.toByteArray(recordingStream(true));
    assertArrayEquals(expectedBytes, rawJfr.get());
  }

  @ParameterizedTest
  @ValueSource(strings = {"on", "lz4", "gzip", "zstd", "off", "invalid"})
  public void testCompression(final String compression) throws Exception {
    when(config.getApiKey()).thenReturn(null);
    when(config.getProfilingUploadCompression()).thenReturn(compression);
    when(config.getProfilingUploadTimeout()).thenReturn(500000);
    uploader = new ProfileUploader(config, configProvider);

    server.enqueue(new MockResponse().setResponseCode(200));

    uploadAndWait(RECORDING_TYPE, mockRecordingData());

    final RecordedRequest recordedRequest = server.takeRequest(5, TimeUnit.SECONDS);
    assertEquals(url, recordedRequest.getRequestUrl());

    assertNull(recordedRequest.getHeader("DD-API-KEY"));

    final List<FileItem> multiPartItems =
        FileUpload.parse(
            recordedRequest.getBody().readByteArray(), recordedRequest.getHeader("Content-Type"));

    final FileItem rawEvent = multiPartItems.get(0);
    assertEquals(ProfileUploader.V4_EVENT_NAME, rawEvent.getFieldName());
    assertEquals(ProfileUploader.V4_EVENT_FILENAME, rawEvent.getName());
    assertEquals("application/json", rawEvent.getContentType());

    final FileItem rawJfr = multiPartItems.get(1);
    assertEquals(ProfileUploader.V4_ATTACHMENT_NAME, rawJfr.getFieldName());
    assertEquals(ProfileUploader.V4_ATTACHMENT_FILENAME, rawJfr.getName());
    assertEquals("application/octet-stream", rawJfr.getContentType());

    final byte[] expectedBytes = ByteStreams.toByteArray(recordingStream(false));

    byte[] uploadedBytes = rawJfr.get();
    if (compression.equals("gzip")) {
      uploadedBytes = unGzip(uploadedBytes);
    } else if (compression.equals("zstd")
        || compression.equals("on")
        || compression.equals("invalid")) {
      uploadedBytes = unZstd(uploadedBytes);
    } else if (compression.equals("lz4")) {
      uploadedBytes = unLz4(uploadedBytes);
    }
    assertArrayEquals(expectedBytes, uploadedBytes);
  }

  @Test
  public void testRequestWithContainerId() throws Exception {
    uploader =
        new ProfileUploader(
            config, configProvider, ioLogger, (int) TERMINATION_TIMEOUT.getSeconds());

    server.enqueue(new MockResponse().setResponseCode(200));
    uploadAndWait(RECORDING_TYPE, mockRecordingData());

    final RecordedRequest recordedRequest = server.takeRequest(5, TimeUnit.SECONDS);
    assertNotNull(recordedRequest);
  }

  @Test
  public void testAgentRequestWithApiKey() throws Exception {
    when(config.getApiKey()).thenReturn(API_KEY_VALUE);

    uploader = new ProfileUploader(config, configProvider);
    server.enqueue(new MockResponse().setResponseCode(200));
    uploadAndWait(RECORDING_TYPE, mockRecordingData());

    final RecordedRequest recordedRequest = server.takeRequest(5, TimeUnit.SECONDS);
    assertNotNull(recordedRequest);
    assertNull(recordedRequest.getHeader("DD-API-KEY"));
  }

  @Test
  public void testAgentlessRequest() throws Exception {
    when(config.getApiKey()).thenReturn(API_KEY_VALUE);
    when(config.isProfilingAgentless()).thenReturn(true);

    uploader = new ProfileUploader(config, configProvider);
    server.enqueue(new MockResponse().setResponseCode(200));
    uploadAndWait(RECORDING_TYPE, mockRecordingData());

    final RecordedRequest recordedRequest = server.takeRequest(5, TimeUnit.SECONDS);
    assertNotNull(recordedRequest);
    assertEquals(API_KEY_VALUE, recordedRequest.getHeader("DD-API-KEY"));
  }

  @Test
  public void test404() throws Exception {
    // test added to get the coverage checks to pass since we log conditionally in this case
    when(config.getApiKey()).thenReturn(null);

    uploader = new ProfileUploader(config, configProvider);
    server.enqueue(new MockResponse().setResponseCode(404));
    uploadAndWait(RECORDING_TYPE, mockRecordingData());

    final RecordedRequest recordedRequest = server.takeRequest(5, TimeUnit.SECONDS);
    assertNotNull(recordedRequest);
    assertNull(recordedRequest.getHeader("DD-API-KEY"));
    // it would be nice if the test asserted the log line was written out, but it's not essential
  }

  @Test
  public void test404Agentless() throws Exception {
    // test added to get the coverage checks to pass since we log conditionally in this case
    when(config.getApiKey()).thenReturn(API_KEY_VALUE);
    when(config.isProfilingAgentless()).thenReturn(true);

    uploader = new ProfileUploader(config, configProvider);
    server.enqueue(new MockResponse().setResponseCode(404));
    uploadAndWait(RECORDING_TYPE, mockRecordingData());

    final RecordedRequest recordedRequest = server.takeRequest(5, TimeUnit.SECONDS);
    assertNotNull(recordedRequest);
    assertEquals(API_KEY_VALUE, recordedRequest.getHeader("DD-API-KEY"));
  }

  @Test
  public void testRequestWithProxy() throws Exception {
    final String backendHost = "intake.profiling.datadoghq.com:1234";
    final String backendUrl = "http://intake.profiling.datadoghq.com:1234" + URL_PATH;
    when(config.getFinalProfilingUrl())
        .thenReturn("http://intake.profiling.datadoghq.com:1234" + URL_PATH);
    when(config.getProfilingProxyHost()).thenReturn(server.url("").host());
    when(config.getProfilingProxyPort()).thenReturn(server.url("").port());
    when(config.getProfilingProxyUsername()).thenReturn("username");
    when(config.getProfilingProxyPassword()).thenReturn("password");

    uploader = new ProfileUploader(config, configProvider);

    server.enqueue(new MockResponse().setResponseCode(407).addHeader("Proxy-Authenticate: Basic"));
    server.enqueue(new MockResponse().setResponseCode(200));

    uploadAndWait(RECORDING_TYPE, mockRecordingData());

    final RecordedRequest recordedFirstRequest = server.takeRequest(5, TimeUnit.SECONDS);
    assertEquals(server.url(""), recordedFirstRequest.getRequestUrl());
    assertNull(recordedFirstRequest.getHeader("Proxy-Authorization"));
    assertEquals(backendHost, recordedFirstRequest.getHeader("Host"));
    assertEquals(
        String.format("POST %s HTTP/1.1", backendUrl), recordedFirstRequest.getRequestLine());

    final RecordedRequest recordedSecondRequest = server.takeRequest(5, TimeUnit.SECONDS);
    assertEquals(server.url(""), recordedSecondRequest.getRequestUrl());
    assertEquals(
        Credentials.basic("username", "password"),
        recordedSecondRequest.getHeader("Proxy-Authorization"));
    assertEquals(backendHost, recordedSecondRequest.getHeader("Host"));
    assertEquals(
        String.format("POST %s HTTP/1.1", backendUrl), recordedSecondRequest.getRequestLine());
  }

  @Test
  public void testRequestWithProxyDefaultPassword() throws Exception {
    final String backendUrl = "http://intake.profiling.datadoghq.com:1234" + URL_PATH;
    when(config.getFinalProfilingUrl())
        .thenReturn("http://intake.profiling.datadoghq.com:1234" + URL_PATH);
    when(config.getProfilingProxyHost()).thenReturn(server.url("").host());
    when(config.getProfilingProxyPort()).thenReturn(server.url("").port());
    when(config.getProfilingProxyUsername()).thenReturn("username");

    uploader = new ProfileUploader(config, configProvider);

    server.enqueue(new MockResponse().setResponseCode(407).addHeader("Proxy-Authenticate: Basic"));
    server.enqueue(new MockResponse().setResponseCode(200));

    uploadAndWait(RECORDING_TYPE, mockRecordingData());

    final RecordedRequest recordedFirstRequest = server.takeRequest(5, TimeUnit.SECONDS);
    final RecordedRequest recordedSecondRequest = server.takeRequest(5, TimeUnit.SECONDS);
    assertEquals(
        Credentials.basic("username", ""), recordedSecondRequest.getHeader("Proxy-Authorization"));
  }

  @Test
  void testOkHttpClientForcesCleartextConnspecWhenNotUsingTLS() {
    when(config.getFinalProfilingUrl()).thenReturn("http://example.com");

    uploader = new ProfileUploader(config, configProvider);

    final List<ConnectionSpec> connectionSpecs = uploader.getClient().connectionSpecs();
    assertEquals(connectionSpecs.size(), 1);
    assertTrue(connectionSpecs.contains(ConnectionSpec.CLEARTEXT));
  }

  @Test
  void testOkHttpClientUsesDefaultConnspecsOverTLS() {
    when(config.getFinalProfilingUrl()).thenReturn("https://example.com");

    uploader = new ProfileUploader(config, configProvider);

    final List<ConnectionSpec> connectionSpecs = uploader.getClient().connectionSpecs();
    assertEquals(connectionSpecs.size(), 2);
    assertTrue(connectionSpecs.contains(ConnectionSpec.MODERN_TLS));
    assertTrue(connectionSpecs.contains(ConnectionSpec.CLEARTEXT));
  }

  @Test
  public void testRecordingClosed() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(200));

    final RecordingData recording = mockRecordingData();
    uploadAndWait(RECORDING_TYPE, recording);

    verify(recording).release();
  }

  @Test
  public void test500Response() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(500));

    final RecordingData recording = mockRecordingData();
    uploadAndWait(RECORDING_TYPE, recording);

    assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));

    verify(recording).release();
  }

  @Test
  public void test413Response() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(413));

    final RecordingData recording = mockRecordingData();
    uploadAndWait(RECORDING_TYPE, recording);

    assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));

    verify(recording).release();

    // J9 has 'almost' implemented JFR, but not really
    // we need to skip this part for J9
    if (!JavaVirtualMachine.isJ9()
        && Files.exists(Paths.get(System.getProperty("java.home"), "bin", "jfr"))) {
      verify(ioLogger)
          .error(
              eq("Failed to upload profile, it's too big. Dumping information about the profile"));
      verify(ioLogger, times(10)).error(matches("Event: .*, size = [0-9]+, count = [0-9]+"));
    } else {
      verify(ioLogger).error(eq("Failed to gather information on recording, can't find `jfr`"));
    }
  }

  @Test
  public void testConnectionRefused() throws Exception {
    server.shutdown();

    final RecordingData recording = mockRecordingData();
    uploadAndWait(RECORDING_TYPE, recording);

    verify(recording).release();

    // Shutting down uploader ensures all callbacks are called on http client
    uploader.shutdown();
    verify(ioLogger).error(eq("Failed to upload profile to " + url), any(ConnectException.class));
  }

  @Test
  public void testConnectionRefusedSync() throws Exception {
    server.shutdown();

    final RecordingData recording = mockRecordingData();
    uploader.upload(RECORDING_TYPE, recording, true);

    verify(recording).release();

    // Shutting down uploader ensures all callbacks are called on http client
    uploader.shutdown();
    verify(ioLogger).error(eq("Failed to upload profile to " + url), any(ConnectException.class));
  }

  @Test
  public void testNoReplyFromServer() throws Exception {
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
    final RecordingData recording = mockRecordingData();
    uploadAndWait(RECORDING_TYPE, recording);

    // Wait longer than request timeout
    assertNotNull(server.takeRequest(REQUEST_TIMEOUT.getSeconds() + 1, TimeUnit.SECONDS));

    // Shutting down uploader ensures all callbacks are called on http client
    uploader.shutdown();
    verify(recording).release();
    verify(ioLogger)
        .error(
            eq(
                "Failed to upload profile, received empty reply from "
                    + url
                    + " after uploading profile"));
  }

  @Test
  public void testNoReplyFromServerSync() throws Exception {
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
    final RecordingData recording = mockRecordingData();
    uploader.upload(RECORDING_TYPE, recording, true);

    // Wait longer than request timeout
    assertNotNull(server.takeRequest(REQUEST_TIMEOUT.getSeconds() + 1, TimeUnit.SECONDS));

    // Shutting down uploader ensures all callbacks are called on http client
    uploader.shutdown();
    verify(recording).release();
    verify(ioLogger)
        .error(
            eq(
                "Failed to upload profile, received empty reply from "
                    + url
                    + " after uploading profile"));
  }

  @Test
  public void testSyncDoesNotStayBlocked() throws Exception {
    // We need to muck around with the 'uploadTimeout' field to actually force
    // the uploader to take the 'safety-break' route. Otherwise, the request
    // will always fail first on socket timeouts.
    Field fld = ProfileUploader.class.getDeclaredField("uploadTimeout");
    fld.setAccessible(true);
    fld.set(uploader, Duration.ofSeconds(1));
    // ---

    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.STALL_SOCKET_AT_START));
    final RecordingData recording = mockRecordingData();
    uploader.upload(RECORDING_TYPE, recording, true);

    // Wait longer than termination timeout
    assertNotNull(server.takeRequest(REQUEST_TIMEOUT.getSeconds() + 1, TimeUnit.SECONDS));

    // Shutting down uploader ensures all callbacks are called on http client
    uploader.shutdown();
    verify(recording).release();
    verify(ioLogger).error(eq("Failed to upload profile to " + url), (Exception) isNull());
  }

  @Test
  public void testTimeout() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeadersDelay(
                REQUEST_IO_OPERATION_TIMEOUT.plus(Duration.ofSeconds(1)).toMillis(),
                TimeUnit.MILLISECONDS));

    final RecordingData recording = mockRecordingData();
    uploadAndWait(RECORDING_TYPE, recording);

    // Wait longer than request timeout
    assertNotNull(
        server.takeRequest(REQUEST_IO_OPERATION_TIMEOUT.getSeconds() + 2, TimeUnit.SECONDS));

    // Shutting down uploader ensures all callbacks are called on http client
    uploader.shutdown();
    verify(recording).release();
    // This seems to be a weird behaviour on okHttp side: it considers request to be a success even
    // if it didn't get headers before the timeout
    verify(ioLogger).success(eq("Upload done"));
  }

  @Test
  public void testTimeoutSync() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeadersDelay(
                REQUEST_IO_OPERATION_TIMEOUT.plus(Duration.ofSeconds(1)).toMillis(),
                TimeUnit.MILLISECONDS));

    final RecordingData recording = mockRecordingData();
    uploader.upload(RECORDING_TYPE, recording, true);

    // Wait longer than request timeout
    assertNotNull(
        server.takeRequest(REQUEST_IO_OPERATION_TIMEOUT.getSeconds() + 10, TimeUnit.SECONDS));

    // Shutting down uploader ensures all callbacks are called on http client
    uploader.shutdown();
    verify(recording).release();
    // This seems to be a weird behaviour on okHttp side: it considers request to be a success even
    // if it didn't get headers before the timeout
    verify(ioLogger).success(eq("Upload done"));
  }

  @Test
  public void testUnfinishedRecording() throws Exception {
    final RecordingData recording = mockRecordingData();
    when(recording.getStream()).thenThrow(new IllegalStateException("test exception"));
    uploadAndWait(RECORDING_TYPE, recording);

    verify(recording).release();
    verify(recording, times(2)).getStream();
  }

  @Test
  public void testEmptyRecording() throws Exception {
    final RecordingData recording = mockRecordingData();
    when(recording.getStream())
        .then(
            (Answer<RecordingInputStream>)
                instance -> new RecordingInputStream(new ByteArrayInputStream(new byte[0])));
    server.enqueue(new MockResponse().setResponseCode(200));
    uploadAndWait(RECORDING_TYPE, recording);

    final RecordedRequest recordedRequest = server.takeRequest(500, TimeUnit.MILLISECONDS);
    assertNull(recordedRequest);
  }

  @Test
  public void testEnqueuedRequestsExecuted() throws Exception {
    // We have to block all parallel requests to make sure queue is kept full
    for (int i = 0; i < ProfileUploader.MAX_RUNNING_REQUESTS; i++) {
      server.enqueue(
          new MockResponse()
              .setHeadersDelay(
                  // 1 second should be enough to schedule all requests and not hit timeout
                  Duration.ofSeconds(1).toMillis(), TimeUnit.MILLISECONDS)
              .setResponseCode(200));
    }
    server.enqueue(new MockResponse().setResponseCode(200));

    for (int i = 0; i < ProfileUploader.MAX_RUNNING_REQUESTS; i++) {
      final RecordingData recording = mockRecordingData();
      uploadAndWait(RECORDING_TYPE, recording);
    }

    final RecordingData additionalRecording = mockRecordingData();
    uploadAndWait(RECORDING_TYPE, additionalRecording);

    // Make sure all expected requests happened
    for (int i = 0; i < ProfileUploader.MAX_RUNNING_REQUESTS; i++) {
      assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
    }

    assertNotNull(server.takeRequest(2000, TimeUnit.MILLISECONDS), "Got enqueued request");

    verify(additionalRecording).release();
  }

  @Test
  public void testTooManyRequests() throws Exception {
    // We need to make sure that initial requests that fill up the queue hang to the duration of the
    // test. So we specify insanely large timeout here.
    when(config.getProfilingUploadTimeout()).thenReturn((int) FOREVER_REQUEST_TIMEOUT.getSeconds());
    uploader = new ProfileUploader(config, configProvider);

    // We have to block all parallel requests to make sure queue is kept full
    for (int i = 0; i < ProfileUploader.MAX_RUNNING_REQUESTS; i++) {
      server.enqueue(
          new MockResponse()
              .setHeadersDelay(FOREVER_REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
              .setResponseCode(200));
    }
    server.enqueue(new MockResponse().setResponseCode(200));

    final List<RecordingData> inflightRecordings = new ArrayList<>();
    for (int i = 0; i < ProfileUploader.MAX_RUNNING_REQUESTS; i++) {
      final RecordingData recording = mockRecordingData();
      inflightRecordings.add(recording);
      uploader.upload(RECORDING_TYPE, recording);
    }

    for (int i = 0; i < ProfileUploader.MAX_ENQUEUED_REQUESTS; i++) {
      final RecordingData recording = mockRecordingData();
      inflightRecordings.add(recording);
      uploader.upload(RECORDING_TYPE, recording);
    }

    // We schedule one additional request to check case when request would be rejected immediately
    // rather than added to the queue.
    final RecordingData rejectedRecording = mockRecordingData();
    uploader.upload(RECORDING_TYPE, rejectedRecording);

    // Make sure all expected requests happened
    for (int i = 0; i < ProfileUploader.MAX_RUNNING_REQUESTS; i++) {
      assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
    }
    // Recordings after RecordingUploader.MAX_RUNNING_REQUESTS will not be executed because number
    // or parallel requests has been reached.
    assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS), "No more requests");

    // the hung-up running requests and the enqueued requests can not have the recording data
    // released
    for (final RecordingData data : inflightRecordings) {
      verify(data, VerificationModeFactory.times(0)).release();
    }
    // however, the rejected recording should have the recording data released
    verify(rejectedRecording).release();
  }

  @Test
  public void testShutdown() throws Exception {
    uploader.shutdown();

    final RecordingData recording = mockRecordingData();
    uploadAndWait(RECORDING_TYPE, recording);

    assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS), "No more requests");

    verify(recording).release();
  }

  @ParameterizedTest(name = "process tags enabled ''{0}''")
  @ValueSource(booleans = {true, false})
  public void testRequestWithProcessTags(boolean processTagsEnabled) throws Exception {
    when(config.isExperimentalPropagateProcessTagsEnabled()).thenReturn(processTagsEnabled);
    ProcessTags.reset(config);
    uploader =
        new ProfileUploader(
            config, configProvider, ioLogger, (int) TERMINATION_TIMEOUT.getSeconds());

    server.enqueue(new MockResponse().setResponseCode(200));
    uploadAndWait(RECORDING_TYPE, mockRecordingData());

    final RecordedRequest recordedRequest = server.takeRequest(5, TimeUnit.SECONDS);
    assertNotNull(recordedRequest);
    final List<FileItem> multiPartItems =
        FileUpload.parse(
            recordedRequest.getBody().readByteArray(), recordedRequest.getHeader("Content-Type"));

    final FileItem rawEvent = multiPartItems.get(0);
    final Map<String, ?> parsed = new ObjectMapper().readValue(rawEvent.get(), Map.class);
    if (processTagsEnabled) {
      assertNotNull(ProcessTags.getTagsForSerialization());
      assertEquals(ProcessTags.getTagsForSerialization().toString(), parsed.get("process_tags"));
    } else {
      assertNull(parsed.get("process_tags"));
    }
  }

  private RecordingData mockRecordingData() throws IOException {
    return mockRecordingData(false, ProfilingSnapshot.Kind.PERIODIC);
  }

  private RecordingData mockRecordingData(final boolean zip) throws IOException {
    return mockRecordingData(zip, ProfilingSnapshot.Kind.PERIODIC);
  }

  private RecordingData mockRecordingData(final boolean zip, ProfilingSnapshot.Kind kind)
      throws IOException {
    final RecordingData recordingData = mock(RecordingData.class, withSettings().lenient());
    when(recordingData.getStream())
        .then(
            (Answer<InputStream>)
                invocation -> spy(new RecordingInputStream(recordingStream(zip))));
    when(recordingData.getName()).thenReturn(RECORDING_NAME_PREFIX + SEQUENCE_NUMBER);
    when(recordingData.getStart()).thenReturn(Instant.ofEpochSecond(PROFILE_START));
    when(recordingData.getEnd()).thenReturn(Instant.ofEpochSecond(PROFILE_END));
    when(recordingData.getKind()).thenReturn(kind);
    return recordingData;
  }

  private static byte[] unGzip(final byte[] compressed) throws IOException {
    final InputStream stream = new GZIPInputStream(new ByteArrayInputStream(compressed));
    final ByteArrayOutputStream result = new ByteArrayOutputStream();
    ByteStreams.copy(stream, result);
    return result.toByteArray();
  }

  private static byte[] unLz4(final byte[] compressed) throws IOException {
    final InputStream stream = new LZ4FrameInputStream(new ByteArrayInputStream(compressed));
    final ByteArrayOutputStream result = new ByteArrayOutputStream();
    ByteStreams.copy(stream, result);
    return result.toByteArray();
  }

  private static byte[] unZstd(final byte[] compressed) throws IOException {
    final InputStream stream = new ZstdInputStream(new ByteArrayInputStream(compressed));
    final ByteArrayOutputStream result = new ByteArrayOutputStream();
    ByteStreams.copy(stream, result);
    return result.toByteArray();
  }

  private void uploadAndWait(final RecordingType recordingType, final RecordingData data)
      throws InterruptedException {
    final CountDownLatch latch = new CountDownLatch(1);
    uploader.upload(recordingType, data, latch::countDown);
    latch.await();
  }

  private static InputStream recordingStream(final boolean gzip) throws IOException {
    InputStream dataStream = ProfileUploader.class.getResourceAsStream(RECORDING_RESOURCE);
    if (gzip) {
      final ByteArrayOutputStream baos = new ByteArrayOutputStream();
      try (final GZIPOutputStream zos = new GZIPOutputStream(baos)) {
        IOUtils.copy(dataStream, zos);
      }
      dataStream = new ByteArrayInputStream(baos.toByteArray());
    }
    return new BufferedInputStream(dataStream);
  }
}
