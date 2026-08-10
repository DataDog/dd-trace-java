package datadog.trace.civisibility.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.communication.BackendApi;
import datadog.communication.EvpProxyApi;
import datadog.communication.IntakeApi;
import datadog.communication.http.HttpRetryPolicy;
import datadog.communication.http.OkHttpUtils;
import datadog.trace.agent.test.server.http.JavaTestHttpServer;
import datadog.trace.api.civisibility.config.TestFQN;
import datadog.trace.api.civisibility.telemetry.NoOpMetricCollector;
import datadog.trace.api.intake.Intake;
import datadog.trace.civisibility.config.api.dto.request.TracerEnvironment;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;

class ConfigurationApiImplTest extends AbstractConfigurationApiContractTest {

  private static final int REQUEST_TIMEOUT_MILLIS = 15_000;

  private static final String REQUEST_UID = "1234";

  // Backend path each endpoint listens on (relative to /api/v2/).
  private static final Map<Endpoint, String> ENDPOINT_PATHS = new HashMap<>();

  static {
    ENDPOINT_PATHS.put(Endpoint.SETTINGS, "/api/v2/libraries/tests/services/setting");
    ENDPOINT_PATHS.put(Endpoint.SKIPPABLE_TESTS, "/api/v2/ci/tests/skippable");
    ENDPOINT_PATHS.put(Endpoint.FLAKY_TESTS, "/api/v2/ci/libraries/tests/flaky");
    ENDPOINT_PATHS.put(Endpoint.KNOWN_TESTS, "/api/v2/ci/libraries/tests");
    ENDPOINT_PATHS.put(Endpoint.TEST_MANAGEMENT, "/api/v2/test/libraries/test-management/tests");
  }

  private final List<JavaTestHttpServer> servers = new ArrayList<>();

  @AfterEach
  void closeServers() {
    for (JavaTestHttpServer server : servers) {
      server.close();
    }
    servers.clear();
  }

  @Override
  protected ConfigurationApi apiReturning(Endpoint endpoint, String responseBody) {
    JavaTestHttpServer server =
        JavaTestHttpServer.httpServer(
            s ->
                s.handlers(
                    h ->
                        h.prefix(
                            ENDPOINT_PATHS.get(endpoint),
                            api -> sendResponseBody(api, responseBody.getBytes()))));
    servers.add(server);
    return givenConfigurationApi(server, true, true);
  }

  // A response payload that exists only to keep the round-trip happy: parse correctness for the
  // settings endpoint is covered by AbstractConfigurationApiContractTest#parsesSettings.
  private static final CiVisibilitySettings CANONICAL_SETTINGS_RESPONSE =
      new CiVisibilitySettings(
          false,
          false,
          false,
          false,
          false,
          false,
          false,
          false,
          false,
          EarlyFlakeDetectionSettings.DEFAULT,
          TestManagementSettings.DEFAULT,
          null,
          false);

  static Stream<Arguments> testSettingsRequestArguments() {
    return Stream.of(
        arguments("agentless=false, compression=false", false, false),
        arguments("agentless=false, compression=true", false, true),
        arguments("agentless=true, compression=false", true, false),
        arguments("agentless=true, compression=true", true, true));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("testSettingsRequestArguments")
  void testSettingsRequest(String scenario, boolean agentless, boolean compression)
      throws IOException {
    TracerEnvironment tracerEnvironment = givenTracerEnvironment(null);

    Map<String, Object> requestData = new HashMap<>();
    requestData.put("uid", REQUEST_UID);
    requestData.put("tracerEnvironment", tracerEnvironment);
    Map<String, Object> responseData = new HashMap<>();
    responseData.put("settings", CANONICAL_SETTINGS_RESPONSE);

    JavaTestHttpServer intakeServer =
        givenBackendEndpoint(
            "/api/v2/libraries/tests/services/setting",
            "settings-request.ftl",
            requestData,
            "settings-response.ftl",
            responseData);
    ConfigurationApi configurationApi = givenConfigurationApi(intakeServer, agentless, compression);

    configurationApi.getSettings(tracerEnvironment);
  }

  static Stream<Arguments> testSkippableTestsRequestArguments() {
    return Stream.of(
        arguments("no test bundle", null, "skippable-request.ftl"),
        arguments("with test bundle", "testBundle-a", "skippable-request-one-module.ftl"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("testSkippableTestsRequestArguments")
  void testSkippableTestsRequest(String scenario, String testBundle, String requestTemplate)
      throws IOException {
    TracerEnvironment tracerEnvironment = givenTracerEnvironment(testBundle);

    Map<String, Object> requestData = new HashMap<>();
    requestData.put("uid", REQUEST_UID);
    requestData.put("tracerEnvironment", tracerEnvironment);

    JavaTestHttpServer intakeServer =
        givenBackendEndpoint(
            "/api/v2/ci/tests/skippable",
            requestTemplate,
            requestData,
            "skippable-response.ftl",
            new HashMap<>());
    ConfigurationApi configurationApi = givenConfigurationApi(intakeServer, true, true);

    configurationApi.getSkippableTests(tracerEnvironment);
  }

  static Stream<Arguments> testFlakyTestsRequestArguments() {
    return Stream.of(
        arguments("no test bundle", null, "flaky-request.ftl"),
        arguments("with test bundle", "testBundle-a", "flaky-request-one-module.ftl"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("testFlakyTestsRequestArguments")
  void testFlakyTestsRequest(String scenario, String testBundle, String requestTemplate)
      throws IOException {
    TracerEnvironment tracerEnvironment = givenTracerEnvironment(testBundle);

    Map<String, Object> requestData = new HashMap<>();
    requestData.put("uid", REQUEST_UID);
    requestData.put("tracerEnvironment", tracerEnvironment);

    JavaTestHttpServer intakeServer =
        givenBackendEndpoint(
            "/api/v2/ci/libraries/tests/flaky",
            requestTemplate,
            requestData,
            "flaky-response.ftl",
            new HashMap<>());
    ConfigurationApi configurationApi = givenConfigurationApi(intakeServer, true, true);

    configurationApi.getFlakyTestsByModule(tracerEnvironment);
  }

  @Test
  void testKnownTestsRequestWithPagination() throws IOException {
    TracerEnvironment tracerEnvironment = givenTracerEnvironment(null);
    AtomicInteger requestCount = new AtomicInteger(0);

    String page1 =
        "{\"data\":{\"id\":\"page1\",\"type\":\"ci_app_libraries_tests\",\"attributes\":{\"tests\":{\"module-a\":{\"suite-a\":[\"test-1\",\"test-2\"]}},\"page_info\":{\"size\":2,\"has_next\":true,\"cursor\":\"cursor-page-2\"}}}}";
    String page2 =
        "{\"data\":{\"id\":\"page2\",\"type\":\"ci_app_libraries_tests\",\"attributes\":{\"tests\":{\"module-b\":{\"suite-b\":[\"test-3\",\"test-4\"]}},\"page_info\":{\"size\":2,\"has_next\":true,\"cursor\":\"cursor-page-3\"}}}}";
    String page3 =
        "{\"data\":{\"id\":\"page3\",\"type\":\"ci_app_libraries_tests\",\"attributes\":{\"tests\":{\"module-c\":{\"suite-c\":[\"test-5\",\"test-6\"]}},\"page_info\":{\"size\":2,\"has_next\":false}}}}";
    List<String> responses = Arrays.asList(page1, page2, page3);

    JavaTestHttpServer intakeServer =
        JavaTestHttpServer.httpServer(
            s ->
                s.handlers(
                    h ->
                        h.prefix(
                            "/api/v2/ci/libraries/tests",
                            api -> {
                              int current = requestCount.incrementAndGet();
                              sendResponseBody(api, responses.get(current - 1).getBytes());
                            })));
    servers.add(intakeServer);
    ConfigurationApi configurationApi = givenConfigurationApi(intakeServer, true, true);

    Map<String, Collection<TestFQN>> knownTests =
        configurationApi.getKnownTestsByModule(tracerEnvironment);
    sortValues(knownTests);

    assertEquals(3, requestCount.get());

    Map<String, Collection<TestFQN>> expected = new LinkedHashMap<>();
    expected.put(
        "module-a",
        Arrays.asList(new TestFQN("suite-a", "test-1"), new TestFQN("suite-a", "test-2")));
    expected.put(
        "module-b",
        Arrays.asList(new TestFQN("suite-b", "test-3"), new TestFQN("suite-b", "test-4")));
    expected.put(
        "module-c",
        Arrays.asList(new TestFQN("suite-c", "test-5"), new TestFQN("suite-c", "test-6")));
    assertEquals(expected, knownTests);
  }

  private ConfigurationApi givenConfigurationApi(
      JavaTestHttpServer intakeServer, boolean agentless, boolean compression) {
    BackendApi api =
        agentless
            ? givenIntakeApi(intakeServer.getAddress(), compression)
            : givenEvpProxy(intakeServer.getAddress(), compression);
    return new ConfigurationApiImpl(api, NoOpMetricCollector.INSTANCE, () -> REQUEST_UID);
  }

  private JavaTestHttpServer givenBackendEndpoint(
      String path,
      String requestTemplate,
      Map<String, Object> requestData,
      String responseTemplate,
      Map<String, Object> responseData)
      throws IOException {
    String expectedRequestBody = render(requestTemplate, requestData);
    byte[] responseBody = render(responseTemplate, responseData).getBytes();
    JavaTestHttpServer server =
        JavaTestHttpServer.httpServer(
            s ->
                s.handlers(
                    h ->
                        h.prefix(
                            path,
                            api -> {
                              JavaTestHttpServer.HandlerApi.ResponseApi response =
                                  api.getResponse();
                              try {
                                JSONAssert.assertEquals(
                                    expectedRequestBody,
                                    new String(api.getRequest().getBody()),
                                    JSONCompareMode.LENIENT);
                              } catch (AssertionError error) {
                                response.status(400).send(error.getMessage().getBytes());
                                return;
                              }
                              sendResponseBody(api, responseBody);
                            })));
    servers.add(server);
    return server;
  }

  private static void sendResponseBody(JavaTestHttpServer.HandlerApi api, byte[] body) {
    JavaTestHttpServer.HandlerApi.ResponseApi response = api.getResponse();
    String header = api.getRequest().getHeader("Accept-Encoding");
    if (header != null && header.contains("gzip")) {
      response.addHeader("Content-Encoding", "gzip");
      body = gzip(body);
    }
    response.status(200).send(body);
  }

  private static byte[] gzip(byte[] payload) {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (GZIPOutputStream zip = new GZIPOutputStream(baos)) {
      IOUtils.copy(new ByteArrayInputStream(payload), zip);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    return baos.toByteArray();
  }

  private BackendApi givenEvpProxy(URI address, boolean responseCompression) {
    String traceId = "a-trace-id";
    HttpUrl proxyUrl = HttpUrl.get(address);
    HttpRetryPolicy.Factory retryPolicyFactory = new HttpRetryPolicy.Factory(5, 100, 2.0);
    OkHttpClient client = OkHttpUtils.buildHttpClient(proxyUrl, REQUEST_TIMEOUT_MILLIS);
    return new EvpProxyApi(
        traceId, proxyUrl, "api", retryPolicyFactory, client, responseCompression);
  }

  private BackendApi givenIntakeApi(URI address, boolean responseCompression) {
    HttpUrl intakeUrl =
        HttpUrl.get(String.format("%s/api/%s/", address.toString(), Intake.API.getVersion()));

    String apiKey = "api-key";
    String traceId = "a-trace-id";

    HttpRetryPolicy retryPolicy = mock(HttpRetryPolicy.class);
    when(retryPolicy.shouldRetry(any(okhttp3.Response.class))).thenReturn(false);

    HttpRetryPolicy.Factory retryPolicyFactory = mock(HttpRetryPolicy.Factory.class);
    when(retryPolicyFactory.create()).thenReturn(retryPolicy);

    OkHttpClient client = OkHttpUtils.buildHttpClient(intakeUrl, REQUEST_TIMEOUT_MILLIS);
    return new IntakeApi(
        intakeUrl, apiKey, traceId, retryPolicyFactory, client, responseCompression);
  }

  private static TracerEnvironment givenTracerEnvironment(String testBundle) {
    return TracerEnvironment.builder()
        .service("foo")
        .env("foo_env")
        .repositoryUrl("https://github.com/DataDog/foo")
        .branch("prod")
        .sha("d64185e45d1722ab3a53c45be47accae")
        .commitMessage("full commit message")
        .osPlatform("linux")
        .osArchitecture("amd64")
        .osVersion("bionic")
        .runtimeName("runtimeName")
        .runtimeVersion("runtimeVersion")
        .runtimeVendor("vendor")
        .runtimeArchitecture("amd64")
        .customTag("customTag", "customValue")
        .testBundle(testBundle)
        .build();
  }

  private static void sortValues(Map<String, Collection<TestFQN>> tests) {
    Comparator<TestFQN> bySuiteThenName =
        Comparator.comparing(TestFQN::getSuite).thenComparing(TestFQN::getName);
    for (Map.Entry<String, Collection<TestFQN>> e : tests.entrySet()) {
      List<TestFQN> sorted = new ArrayList<>(e.getValue());
      sorted.sort(bySuiteThenName);
      e.setValue(sorted);
    }
  }
}
