package datadog.crashtracking;

import static datadog.crashtracking.CrashUploader.HEADER_DD_EVP_SUBDOMAIN;
import static datadog.crashtracking.CrashUploader.HEADER_DD_TELEMETRY_API_VERSION;
import static datadog.crashtracking.CrashUploader.TELEMETRY_API_VERSION;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import datadog.common.version.VersionInfo;
import datadog.crashtracking.dto.CrashLog;
import datadog.environment.SystemProperties;
import datadog.trace.api.Config;
import datadog.trace.util.TraceUtils;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;
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
import org.junit.jupiter.params.provider.ValueSource;

public class CrashUploaderTest {

  private static final String API_KEY_VALUE = "testkey";
  private static final String URL_PATH = "/lalala";
  private static final String CRASH = "this is a crash file";
  private static final String ENV = "crash-env";
  private static final String HOSTNAME = "crash-hostname";
  private static final String SERVICE = "crash-service";
  private static final String VERSION = "crash-version";
  private static final String SAMPLE_UUID = "a4194cd6-8cb3-45fd-9bd9-3af83e0a3ad3";

  // TODO: Add a test to verify overall request timeout rather than IO timeout
  private final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  private Config config = spy(Config.get());

  private final MockWebServer server = new MockWebServer();
  private HttpUrl url;

  private CrashUploader uploader;

  @BeforeEach
  public void setup() throws IOException {
    server.start();
    System.out.println("Setting up test: " + server.getPort());
    url = server.url(URL_PATH);

    when(config.getEnv()).thenReturn(ENV);
    when(config.getHostName()).thenReturn(HOSTNAME);
    when(config.getServiceName()).thenReturn(SERVICE);
    when(config.getVersion()).thenReturn(VERSION);
    when(config.getFinalCrashTrackingTelemetryUrl()).thenReturn(server.url(URL_PATH).toString());
    when(config.getFinalCrashTrackingErrorTrackingUrl())
        .thenReturn(server.url(URL_PATH).toString());
    when(config.isCrashTrackingAgentless()).thenReturn(false);
    when(config.getApiKey()).thenReturn(null);
  }

  @Test
  public void testLogsHappyPath() throws Exception {
    // Given
    ConfigManager.StoredConfig crashConfig = new ConfigManager.StoredConfig.Builder(config).build();
    // When
    uploader = new CrashUploader(config, crashConfig);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    uploader.uploadToLogs(CRASH, new PrintStream(out));

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
    ConfigManager.StoredConfig crashConfig = new ConfigManager.StoredConfig.Builder(config).build();
    uploader = new CrashUploader(config, crashConfig);
    String msg = readFileAsString("sample-crash-redacted.txt");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    uploader.uploadToLogs(msg, new PrintStream(out));

    // Then
    final ObjectMapper mapper = new ObjectMapper();
    final JsonNode event = mapper.readTree(out.toString(StandardCharsets.UTF_8.name()));

    assertEquals("crashtracker", event.get("ddsource").asText());
    assertEquals(HOSTNAME, event.get("hostname").asText());
    assertEquals(SERVICE, event.get("service").asText());
    assertEquals(msg, event.get("message").asText());
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

  private Path getResourcePath(String resourceName) throws Exception {
    return Paths.get(getClass().getClassLoader().getResource(resourceName).toURI());
  }

  @Test
  public void testTelemetryCrashPing() throws Exception {
    // Given
    final String expected = readFileAsString("golden/telemetry/sample-ping-for-telemetry.txt");
    ConfigManager.StoredConfig crashConfig =
        new ConfigManager.StoredConfig.Builder(config)
            .reportUUID(SAMPLE_UUID)
            .processTags("a:b")
            .runtimeId("1234")
            .build();
    // When
    uploader = new CrashUploader(config, crashConfig);
    server.enqueue(new MockResponse().setResponseCode(200));
    uploader.sendPingToTelemetry(null);

    final RecordedRequest recordedRequest = server.takeRequest(5, TimeUnit.SECONDS);

    // Then
    assertEquals(url, recordedRequest.getRequestUrl());
    final ObjectMapper mapper = new ObjectMapper();
    final JsonNode event = mapper.readTree(recordedRequest.getBody().readUtf8());

    // header
    assertCommonHeader(event);

    // payload:
    assertEquals("DEBUG", event.get("payload").get(0).get("level").asText());

    assertFalse(event.get("payload").get(0).get("is_sensitive").asBoolean());
    String tags = event.get("payload").get(0).get("tags").asText();
    assertNotNull(tags);
    assertTrue(tags.contains("is_crash_ping:true"));
    assertTrue(tags.contains(TraceUtils.normalizeTag("service:" + crashConfig.service)));
    assertTrue(tags.contains("uuid:" + crashConfig.reportUUID));
    assertTrue(tags.contains(TraceUtils.normalizeTag("tracer_version:" + VersionInfo.VERSION)));
    assertTrue(tags.contains("language_name:jvm"));
    assertEquals(expected, event.get("payload").get(0).get("message").asText());
    assertCommonPayload(event);
  }

  @Test
  public void testErrorTrackingCrashPing() throws Exception {
    // Given
    final ObjectMapper mapper = new ObjectMapper();
    final Map<String, ?> expected =
        mapper.readValue(readFileAsString("golden/errortracking/sample-ping.txt"), Map.class);
    // remove ddtags and osinfo if present from the expected (they will be checked apart)
    expected.remove("ddtags");
    expected.remove("os_info");
    expected.remove("timestamp");
    ConfigManager.StoredConfig crashConfig =
        new ConfigManager.StoredConfig.Builder(config)
            .reportUUID(SAMPLE_UUID)
            .processTags("a:b")
            .runtimeId("1234")
            .tags(ConfigManager.getMergedTagsForSerialization(Config.get())) // take the real ones
            .build();
    // When
    uploader = new CrashUploader(config, crashConfig);
    server.enqueue(new MockResponse().setResponseCode(200));

    uploader.sendPingToErrorTracking(null);

    final RecordedRequest recordedRequest = server.takeRequest(5, TimeUnit.SECONDS);

    // Then
    assertEquals(url, recordedRequest.getRequestUrl());
    final String requestBody = recordedRequest.getBody().readUtf8();
    final Map<String, ?> extracted = mapper.readValue(requestBody, Map.class);

    assertNotNull(extracted.remove("timestamp"));

    // assert osInfo
    final Map<String, ?> osInfo = (Map<String, ?>) extracted.remove("os_info");

    assertNotNull(osInfo);
    assertNotNull(osInfo.get("architecture"));
    assertNotNull(osInfo.get("version"));
    assertNotNull(osInfo.get("os_type"));
    assertDoesNotThrow(() -> Long.parseLong((String) osInfo.get("bitness"))); // 32 or 64 typically

    // assert ddtags
    String ddtags = (String) extracted.remove("ddtags");
    assertNotNull(ddtags);
    assertTrue(ddtags.contains(crashConfig.tags)); // includes the tags
    assertTrue(ddtags.contains("service:")); // has the service
    assertTrue(ddtags.contains("uuid:" + crashConfig.reportUUID));
    assertTrue(ddtags.contains("is_crash_ping:true"));

    // assert platform independent equality
    assertEquals(
        expected,
        extracted,
        () -> {
          try {
            return "Expected: "
                + mapper.writeValueAsString(expected)
                + "\nbut got: "
                + mapper.writeValueAsString(extracted);
          } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
          }
        });
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "sample-crash-for-telemetry.txt",
        "sample-crash-for-telemetry-2.txt",
        "sample-crash-for-telemetry-3.txt",
        "sample_oom.txt"
      })
  public void testTelemetryHappyPath(String log) throws Exception {
    // Given
    CrashLog expected = CrashLog.fromJson(readFileAsString("golden/telemetry/" + log));
    ConfigManager.StoredConfig crashConfig =
        new ConfigManager.StoredConfig.Builder(config)
            .reportUUID(SAMPLE_UUID)
            .processTags("a:b")
            .runtimeId("1234")
            .build();
    // When
    uploader = new CrashUploader(config, crashConfig);
    server.enqueue(new MockResponse().setResponseCode(200));
    uploader.upload(getResourcePath(log));

    final RecordedRequest recordedRequest = server.takeRequest(5, TimeUnit.SECONDS);

    // Then
    assertEquals(url, recordedRequest.getRequestUrl());

    final ObjectMapper mapper = new ObjectMapper();
    final JsonNode event = mapper.readTree(recordedRequest.getBody().readUtf8());

    // header
    assertCommonHeader(event);

    // payload:
    assertEquals("ERROR", event.get("payload").get(0).get("level").asText());

    assertTrue(event.get("payload").get(0).get("is_sensitive").asBoolean());
    assertTrue(event.get("payload").get(0).get("is_crash").asBoolean());
    String message = event.get("payload").get(0).get("message").asText();
    CrashLog extracted = CrashLog.fromJson(message);

    assertTrue(
        expected.equalsForTest(extracted),
        () -> "Expected: " + expected.toJson() + "\nbut got: " + extracted.toJson());
    assertEquals("severity:crash", event.get("payload").get(0).get("tags").asText());
    assertCommonPayload(event);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "sample-crash-for-telemetry.txt",
        "sample-crash-for-telemetry-2.txt",
        "sample-crash-for-telemetry-3.txt",
        "sample_oom.txt"
      })
  public void testErrorTrackingHappyPath(String log) throws Exception {
    // Given
    final ObjectMapper mapper = new ObjectMapper();
    final Map<String, ?> expected =
        mapper.readValue(readFileAsString("golden/errortracking/" + log), Map.class);
    // remove ddtags and osinfo if present from the expected (they will be checked apart)
    expected.remove("ddtags");
    expected.remove("os_info");
    ConfigManager.StoredConfig crashConfig =
        new ConfigManager.StoredConfig.Builder(config)
            .reportUUID(SAMPLE_UUID)
            .processTags("a:b")
            .runtimeId("1234")
            .tags(ConfigManager.getMergedTagsForSerialization(Config.get())) // take the real ones
            .build();
    // When
    uploader = new CrashUploader(config, crashConfig);
    server.enqueue(new MockResponse().setResponseCode(200));
    uploader.remoteUpload(readFileAsString(log), false, true);

    final RecordedRequest recordedRequest = server.takeRequest(5, TimeUnit.SECONDS);

    // Then
    assertEquals(url, recordedRequest.getRequestUrl());
    final String requestBody = recordedRequest.getBody().readUtf8();
    final Map<String, ?> extracted = mapper.readValue(requestBody, Map.class);

    // assert osInfo
    final Map<String, ?> osInfo = (Map<String, ?>) extracted.remove("os_info");

    assertNotNull(osInfo);
    assertNotNull(osInfo.get("architecture"));
    assertNotNull(osInfo.get("version"));
    assertNotNull(osInfo.get("os_type"));
    assertDoesNotThrow(() -> Long.parseLong((String) osInfo.get("bitness"))); // 32 or 64 typically

    // assert ddtags
    String ddtags = (String) extracted.remove("ddtags");
    assertNotNull(ddtags);
    assertTrue(ddtags.contains(crashConfig.tags)); // includes the tags
    assertTrue(ddtags.contains("service:")); // has the service
    assertTrue(ddtags.contains("uuid:" + crashConfig.reportUUID));
    assertTrue(ddtags.contains("is_crash:true"));

    // assert platform independent equality
    assertEquals(
        expected,
        extracted,
        () -> {
          try {
            return "Expected: "
                + mapper.writeValueAsString(expected)
                + "\nbut got: "
                + mapper.writeValueAsString(extracted);
          } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
          }
        });
  }

  private void assertCommonHeader(JsonNode event) {
    assertEquals(TELEMETRY_API_VERSION, event.get("api_version").asText());
    assertEquals("logs", event.get("request_type").asText());
    assertEquals("crashtracker", event.get("origin").asText());
    assertEquals("1234", event.get("runtime_id").asText());
  }

  private void assertCommonPayload(JsonNode event) {
    // application:
    assertEquals(ENV, event.get("application").get("env").asText());
    assertEquals("jvm", event.get("application").get("language_name").asText());
    assertEquals(
        SystemProperties.getOrDefault("java.version", "unknown"),
        event.get("application").get("language_version").asText());
    assertEquals(SERVICE, event.get("application").get("service_name").asText());
    assertEquals(VERSION, event.get("application").get("service_version").asText());
    assertEquals(VersionInfo.VERSION, event.get("application").get("tracer_version").asText());
    assertEquals("a:b", event.get("application").get("process_tags").asText());
    // host
    assertEquals(HOSTNAME, event.get("host").get("hostname").asText());
    assertEquals(ENV, event.get("host").get("env").asText());
  }

  @Test
  public void testTelemetryUnrecognizedFile() throws Exception {
    // Given
    ConfigManager.StoredConfig crashConfig = new ConfigManager.StoredConfig.Builder(config).build();
    // When
    uploader = new CrashUploader(config, crashConfig);
    server.enqueue(new MockResponse().setResponseCode(200));
    uploader.upload(getResourcePath("no-crash.txt"));
    assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)); // still sending incomplete report
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void testAgentlessRequest(boolean telemetryOrErrorTracking) throws Exception {
    when(config.getApiKey()).thenReturn(API_KEY_VALUE);
    when(config.isCrashTrackingAgentless()).thenReturn(true);
    ConfigManager.StoredConfig crashConfig = new ConfigManager.StoredConfig.Builder(config).build();

    uploader = new CrashUploader(config, crashConfig);
    server.enqueue(new MockResponse().setResponseCode(200));
    uploader.remoteUpload(
        readFileAsString("sample-crash.txt"), telemetryOrErrorTracking, !telemetryOrErrorTracking);

    RecordedRequest recordedRequest = server.takeRequest(5, TimeUnit.SECONDS);
    // common
    assertNotNull(recordedRequest);
    assertEquals(API_KEY_VALUE, recordedRequest.getHeader("DD-API-KEY"));
    if (telemetryOrErrorTracking) {
      // telemetry
      assertEquals(
          TELEMETRY_API_VERSION, recordedRequest.getHeader(HEADER_DD_TELEMETRY_API_VERSION));
    } else {
      assertNull(recordedRequest.getHeader(HEADER_DD_EVP_SUBDOMAIN));
    }
  }

  @Test
  public void test404() throws Exception {
    // test added to get the coverage checks to pass since we log conditionally in this case
    when(config.getApiKey()).thenReturn(null);
    ConfigManager.StoredConfig crashConfig = new ConfigManager.StoredConfig.Builder(config).build();

    uploader = new CrashUploader(config, crashConfig);
    server.enqueue(new MockResponse().setResponseCode(404));
    uploader.upload(getResourcePath("sample-crash.txt"));

    final RecordedRequest recordedRequest = server.takeRequest(5, TimeUnit.SECONDS);
    assertNotNull(recordedRequest);
    assertNull(recordedRequest.getHeader("DD-API-KEY"));
    // it would be nice if the test asserted the log line was written out, but it's not essential
  }

  @Test
  public void testParallelSendResilience() throws Exception {
    try (MockWebServer errorTrackingServer = new MockWebServer()) {
      errorTrackingServer.start();
      // Given
      when(config.getFinalCrashTrackingErrorTrackingUrl())
          .thenReturn(errorTrackingServer.url(URL_PATH).toString());
      final CrashUploader uploader =
          new CrashUploader(config, new ConfigManager.StoredConfig.Builder(config).build());

      // When
      server.enqueue(new MockResponse().setResponseCode(200));
      // simulate a timeout from error tracking
      errorTrackingServer.enqueue(
          new MockResponse().setHeadersDelay(4, TimeUnit.SECONDS).setResponseCode(200));
      long start = System.nanoTime();
      uploader.remoteUpload(readFileAsString("sample-crash.txt"), true, true);
      long elapsed = System.nanoTime() - start;

      // Then
      // assert both backends have been contacted
      assertNotNull(server.takeRequest(1, TimeUnit.SECONDS));
      assertNotNull(errorTrackingServer.takeRequest(1, TimeUnit.SECONDS));
      // assert that we waited
      assertTrue(TimeUnit.NANOSECONDS.toMillis(elapsed) > 2000);
    }
  }

  @Test
  public void test404Agentless() throws Exception {
    // test added to get the coverage checks to pass since we log conditionally in this case
    when(config.getApiKey()).thenReturn(API_KEY_VALUE);
    when(config.isCrashTrackingAgentless()).thenReturn(true);
    ConfigManager.StoredConfig crashConfig = new ConfigManager.StoredConfig.Builder(config).build();

    uploader = new CrashUploader(config, crashConfig);
    server.enqueue(new MockResponse().setResponseCode(404));
    uploader.upload(getResourcePath("sample-crash.txt"));

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
