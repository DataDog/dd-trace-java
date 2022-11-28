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
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import okhttp3.HttpUrl;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class CrashUploaderTest {

  private static final String API_KEY_VALUE = "testkey";
  private static final String URL_PATH = "/lalala";
  private static final String CRASH = "this is a crash file";
  private static final String ENV = "crash-env";
  private static final String HOSTNAME = "crash-hostname";
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
    server.start();
    url = server.url(URL_PATH);

    when(config.getEnv()).thenReturn(ENV);
    when(config.getHostName()).thenReturn(HOSTNAME);
    when(config.getServiceName()).thenReturn(SERVICE);
    when(config.getVersion()).thenReturn(VERSION);
    when(config.getFinalCrashTrackingTelemetryUrl()).thenReturn(server.url(URL_PATH).toString());
    when(config.isCrashTrackingAgentless()).thenReturn(false);
    when(config.getApiKey()).thenReturn(null);
  }

  @Test
  public void testLogsHappyPath() throws Exception {
    // Given

    // When
    uploader = new CrashUploader(config);
    List<String> filesContent = new ArrayList<>();
    filesContent.add(CRASH);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    uploader.uploadToLogs(filesContent, new PrintStream(out));

    // Then
    final ObjectMapper mapper = new ObjectMapper();
    final JsonNode event = mapper.readTree(out.toString(StandardCharsets.UTF_8.name()));

    assertEquals("crashtracker", event.get("ddsource").asText());
    assertEquals(HOSTNAME, event.get("hostname").asText());
    assertEquals(SERVICE, event.get("service").asText());
    assertEquals(CRASH, event.get("message").asText());
    assertEquals("ERROR", event.get("level").asText());
  }

  @Test
  public void testExtractStackTraceFromRealCrashFile() throws IOException {
    uploader = new CrashUploader(config);
    List<String> filesContent = new ArrayList<>();
    filesContent.add(readFileAsString("sample-crash.txt"));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    uploader.uploadToLogs(filesContent, new PrintStream(out));

    // Then
    final ObjectMapper mapper = new ObjectMapper();
    final JsonNode event = mapper.readTree(out.toString(StandardCharsets.UTF_8.name()));

    assertEquals("crashtracker", event.get("ddsource").asText());
    assertEquals(HOSTNAME, event.get("hostname").asText());
    assertEquals(SERVICE, event.get("service").asText());
    assertEquals(filesContent.get(0), event.get("message").asText());
    assertEquals(
        readFileAsString("sample-stacktrace.txt"), event.get("error").get("stack").asText());
    assertEquals("ERROR", event.get("level").asText());
  }

  private String readFileAsString(String resource) throws IOException {
    try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resource)) {
      return new BufferedReader(
              new InputStreamReader(Objects.requireNonNull(stream), StandardCharsets.UTF_8))
          .lines()
          .collect(Collectors.joining("\n"));
    }
  }

  @Test
  public void testTelemetryHappyPath() throws Exception {
    // Given

    // When
    uploader = new CrashUploader(config);
    server.enqueue(new MockResponse().setResponseCode(200));
    List<String> filesContent = new ArrayList<>();
    filesContent.add(CRASH);
    uploader.uploadToTelemetry(filesContent);

    final RecordedRequest recordedRequest = server.takeRequest(5, TimeUnit.SECONDS);

    // Then
    assertEquals(url, recordedRequest.getRequestUrl());

    final ObjectMapper mapper = new ObjectMapper();
    final JsonNode event = mapper.readTree(new String(recordedRequest.getBody().readUtf8()));

    assertEquals(CrashUploader.TELEMETRY_API_VERSION, event.get("api_version").asText());
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
    assertEquals(HOSTNAME, event.get("host").get("hostname").asText());
    assertEquals(ENV, event.get("host").get("env").asText());
  }

  @Test
  public void testAgentlessRequest() throws Exception {
    when(config.getApiKey()).thenReturn(API_KEY_VALUE);
    when(config.isCrashTrackingAgentless()).thenReturn(true);

    uploader = new CrashUploader(config);
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

    uploader = new CrashUploader(config);
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

    uploader = new CrashUploader(config);
    server.enqueue(new MockResponse().setResponseCode(404));
    List<InputStream> files = new ArrayList<>();
    files.add(new ByteArrayInputStream(CRASH.getBytes()));
    uploader.upload(files);

    final RecordedRequest recordedRequest = server.takeRequest(5, TimeUnit.SECONDS);
    assertNotNull(recordedRequest);
    assertEquals(API_KEY_VALUE, recordedRequest.getHeader("DD-API-KEY"));
    // it would be nice if the test asserted the log line was written out, but it's not essential
  }

  @ParameterizedTest
  @MethodSource("extractionArgs")
  void extractInfo(String content, String kind, String msg) throws Exception {
    assertEquals(kind, CrashUploader.extractErrorKind(content));
    assertEquals(msg, CrashUploader.extractErrorMessage(content));
  }

  private static Stream<Arguments> extractionArgs() {
    return Stream.of(
        Arguments.of(
            "# A fatal error has been detected by the Java Runtime Environment:\n"
                + "# SIGSEGV (0xb) at pc=0x00007f696f2022b5, pid=2080369, tid=2082522\n"
                + "# \nOther stuff\n",
            "NativeCrash",
            "SIGSEGV (0xb) at pc=0x00007f696f2022b5, pid=2080369, tid=2082522"),
        Arguments.of(
            "# There is insufficient memory for the Java Runtime Environment to continue.\n"
                + "# SIGSEGV (0xb) at pc=0x00007f696f2022b5, pid=2080369, tid=2082522\n"
                + "# \nOther stuff\n",
            "OutOfMemory",
            "SIGSEGV (0xb) at pc=0x00007f696f2022b5, pid=2080369, tid=2082522"),
        Arguments.of(
            "# Completely unknown error:\n"
                + "# BOOM (0xb) at pc=0x00007f696f2022b5, pid=2080369, tid=2082522\n"
                + "# \nOther stuff\n",
            null,
            null));
  }
}
