package com.datadog.crashtracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import datadog.common.version.VersionInfo;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class CrashUploaderTest {

  private static final String API_KEY_VALUE = "testkey";
  private static final String URL_PATH = "/lalala";
  private static final String CRASH = "this is a crash file";
  private static final String ENV = "crash-env";
  private static final String SERVICE = "crash-service";
  private static final String VERSION = "crash-version";
  // private static final Map<String, String> TAGS = Map.of("foo", "bar", "baz", "123", "null",
  // null, "empty", "");

  // // We sort tags to have expected parameters to have expected result
  // private static final Map<String, String> EXPECTED_TAGS =
  //     ImmutableMap.of(
  //         "baz", "123",
  //         "foo", "bar",
  //         PidHelper.PID_TAG, PidHelper.PID.toString(),
  //         VersionInfo.PROFILER_VERSION_TAG, VersionInfo.VERSION,
  //         VersionInfo.LIBRARY_VERSION_TAG, VersionInfo.VERSION);

  // TODO: Add a test to verify overall request timeout rather than IO timeout
  private final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
  private final Duration REQUEST_IO_OPERATION_TIMEOUT = Duration.ofSeconds(5);

  // Termination timeout has to be longer than request timeout to make sure that all callbacks are
  // called before the termination.
  private final Duration TERMINATION_TIMEOUT = REQUEST_TIMEOUT.plus(Duration.ofSeconds(5));

  private final Duration FOREVER_REQUEST_TIMEOUT = Duration.ofSeconds(1000);

  private Config config = spy(Config.get());
  private ConfigProvider configProvider;

  private final MockWebServer server = new MockWebServer();
  private HttpUrl url;

  private CrashUploader uploader;

  @BeforeEach
  public void setup() throws IOException {
    configProvider = ConfigProvider.getInstance();

    server.start();
    url = server.url(URL_PATH);

    when(config.getEnv()).thenReturn(ENV);
    when(config.getServiceName()).thenReturn(SERVICE);
    when(config.getVersion()).thenReturn(VERSION);
    when(config.getFinalCrashTrackingUrl()).thenReturn(server.url(URL_PATH).toString());
    when(config.isCrashTrackingAgentless()).thenReturn(false);
    when(config.getApiKey()).thenReturn(null);
  }

  @Test
  public void testHappyPath() throws Exception {
    // Given

    // When
    uploader = new CrashUploader(config, configProvider);
    server.enqueue(new MockResponse().setResponseCode(200));
    List<InputStream> files = new ArrayList<>();
    files.add(new ByteArrayInputStream(CRASH.getBytes()));
    uploader.upload(files);

    final RecordedRequest recordedRequest = server.takeRequest(5, TimeUnit.SECONDS);

    // Then
    assertEquals(url, recordedRequest.getRequestUrl());

    final ObjectMapper mapper = new ObjectMapper();
    final JsonNode event = mapper.readTree(new String(recordedRequest.getBody().readUtf8()));

    assertEquals(CrashUploader.API_VERSION, event.get("api_version").asText());
    assertEquals("logs", event.get("request_type").asText());
    // payload:
    assertEquals("ERROR", event.get("payload").get(0).get("level").asText());
    assertEquals(CRASH, event.get("payload").get(0).get("message").asText());
    // application:
    assertEquals(ENV, event.get("application").get("env").asText());
    assertEquals(CrashUploader.JAVA_LANG, event.get("application").get("language_name").asText());
    assertEquals(
        System.getProperty("java.version", "unknown"),
        event.get("application").get("language_version").asText());
    assertEquals(SERVICE, event.get("application").get("service_name").asText());
    assertEquals(VERSION, event.get("application").get("service_version").asText());
    assertEquals(VersionInfo.VERSION, event.get("application").get("tracer_version").asText());
    // host
    assertEquals(ENV, event.get("host").get("env").asText());
  }

  @Test
  public void testAgentlessRequest() throws Exception {
    when(config.getApiKey()).thenReturn(API_KEY_VALUE);
    when(config.isCrashTrackingAgentless()).thenReturn(true);

    uploader = new CrashUploader(config, configProvider);
    server.enqueue(new MockResponse().setResponseCode(200));
    List<InputStream> files = new ArrayList<>();
    files.add(new ByteArrayInputStream(CRASH.getBytes()));
    uploader.upload(files);

    final RecordedRequest recordedRequest = server.takeRequest(5, TimeUnit.SECONDS);
    assertNotNull(recordedRequest);
    assertEquals(API_KEY_VALUE, recordedRequest.getHeader("DD-API-KEY"));
  }

  @Test
  public void test404() throws Exception {
    // test added to get the coverage checks to pass since we log conditionally in this case
    when(config.getApiKey()).thenReturn(null);

    uploader = new CrashUploader(config, configProvider);
    server.enqueue(new MockResponse().setResponseCode(404));
    List<InputStream> files = new ArrayList<>();
    files.add(new ByteArrayInputStream(CRASH.getBytes()));
    uploader.upload(files);

    final RecordedRequest recordedRequest = server.takeRequest(5, TimeUnit.SECONDS);
    assertNotNull(recordedRequest);
    assertNull(recordedRequest.getHeader("DD-API-KEY"));
    // it would be nice if the test asserted the log line was written out, but it's not essential
  }

  @Test
  public void test404Agentless() throws Exception {
    // test added to get the coverage checks to pass since we log conditionally in this case
    when(config.getApiKey()).thenReturn(API_KEY_VALUE);
    when(config.isCrashTrackingAgentless()).thenReturn(true);

    uploader = new CrashUploader(config, configProvider);
    server.enqueue(new MockResponse().setResponseCode(404));
    List<InputStream> files = new ArrayList<>();
    files.add(new ByteArrayInputStream(CRASH.getBytes()));
    uploader.upload(files);

    final RecordedRequest recordedRequest = server.takeRequest(5, TimeUnit.SECONDS);
    assertNotNull(recordedRequest);
    assertEquals(API_KEY_VALUE, recordedRequest.getHeader("DD-API-KEY"));
  }
}
