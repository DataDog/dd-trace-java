package com.datadog.featureflag;

import static com.datadog.featureflag.FeatureFlagEventType.EXPOSURE;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.communication.BackendApi;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.communication.ddagent.TracerVersion;
import datadog.trace.api.Config;
import datadog.trace.api.ProtocolVersion;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.tabletest.junit.TableTest;

class FeatureFlagTransportRegressionTest extends DDJavaSpecification {
  @ParameterizedTest
  @EnumSource(FeatureFlagEventType.class)
  @WithConfig(key = "trace.enabled", value = "false")
  @WithConfig(key = "remote_config.enabled", value = "false")
  @WithConfig(key = "telemetry.enabled", value = "false")
  @WithConfig(key = "appsec.enabled", value = "false")
  @WithConfig(key = "feature.flags.configuration.source", value = "agentless")
  @WithConfig(key = "trace.agent.timeout", value = "2")
  @WithConfig(key = "API_KEY", value = "test-api-key", env = true)
  void agentlessInitializesDirectTimeoutsBeforeAgentDiscovery(FeatureFlagEventType eventType)
      throws Exception {
    try (MockWebServer agent = new MockWebServer()) {
      agent.setDispatcher(
          new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
              return new MockResponse().setBody("{\"endpoints\":[\"v0.5/traces\"]}");
            }
          });
      final SharedCommunicationObjects sco = new SharedCommunicationObjects();
      sco.agentUrl = agent.url("/");
      try {
        assertNotNull(new FeatureFlagBackendApiFactory(Config.get(), sco, eventType).create());

        final OkHttpClient directClient = sco.getIntakeHttpClient();
        assertAll(
            () -> assertEquals(2000, sco.httpClientTimeout),
            () -> assertEquals(2000, directClient.connectTimeoutMillis()),
            () -> assertEquals(2000, directClient.readTimeoutMillis()),
            () -> assertEquals(2000, directClient.writeTimeoutMillis()));
      } finally {
        if (sco.agentHttpClient != null) {
          sco.agentHttpClient.connectionPool().evictAll();
        }
        sco.getIntakeHttpClient().connectionPool().evictAll();
      }
    }
  }

  @TableTest({
    "Scenario              | First Attempt | Attempts | Delivered",
    "refused then accepted | refused       | 2        | true     ",
    "refused throughout    | refused-all   | 4        | false    ",
    "forbidden             | 403           | 1        | false    ",
    "throttled             | 429           | 1        | false    ",
    "internal server error | 500           | 1        | false    ",
    "unavailable           | 503           | 1        | false    ",
    "read timeout          | timeout       | 1        | false    ",
    "reset                 | reset         | 1        | false    ",
    "broken pipe           | broken-pipe   | 1        | false    "
  })
  void remoteConfigRetriesOnlyProvenPreSendFailures(
      String firstAttempt, int attempts, boolean delivered) throws Exception {
    final List<Request> requests = new ArrayList<>();
    final SharedCommunicationObjects sco = new SharedCommunicationObjects();
    sco.agentUrl = HttpUrl.get("http://localhost:8126/");
    sco.agentHttpClient =
        new OkHttpClient.Builder()
            .addInterceptor(
                chain -> {
                  final Request request = chain.request();
                  requests.add(request);
                  if (firstAttempt.equals("refused-all")) {
                    throw new ConnectException("Connection refused");
                  }
                  if (requests.size() == 1) {
                    switch (firstAttempt) {
                      case "refused":
                        throw new ConnectException("Connection refused");
                      case "timeout":
                        throw new SocketTimeoutException("read timeout");
                      case "reset":
                        throw new SocketException("Connection reset");
                      case "broken-pipe":
                        throw new SocketException("Broken pipe");
                      default:
                        return response(request, Integer.parseInt(firstAttempt), "{}");
                    }
                  }
                  return response(request, 202, "{}");
                })
            .build();
    final BackendApi api =
        new FeatureFlagBackendApiFactory(config("remote_config"), sco, EXPOSURE).create();

    if (delivered) {
      post(api);
    } else {
      assertThrows(IOException.class, () -> post(api));
    }

    assertEquals(attempts, requests.size());
    for (Request request : requests) {
      assertEquals("/evp_proxy/v2/api/v2/exposures", request.url().encodedPath());
      assertNull(request.header("DD-API-KEY"));
    }
  }

  @TableTest({
    "Capabilities | Can Deliver",
    "none         | false      ",
    "origin       | false      ",
    "version      | false      ",
    "both         | true       "
  })
  void agentlessIdentityRequiresBothForwardingCapabilities(String capabilities, boolean canDeliver)
      throws Exception {
    final String headers =
        capabilities.equals("both")
            ? "\"dd-evp-origin\",\"DD-EVP-ORIGIN-VERSION\""
            : capabilities.equals("origin")
                ? "\"DD-EVP-ORIGIN\""
                : capabilities.equals("version") ? "\"DD-EVP-ORIGIN-VERSION\"" : "";
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      server.enqueue(
          new MockResponse()
              .setBody(
                  "{\"endpoints\":[\"v0.5/traces\",\"evp_proxy/v4/\"],\"evp_proxy_allowed_headers\":["
                      + headers
                      + "]}"));
      server.enqueue(new MockResponse().setResponseCode(202).setBody("{}"));
      final SharedCommunicationObjects sco = new SharedCommunicationObjects();
      sco.agentUrl = server.url("/");
      sco.agentHttpClient = new OkHttpClient();
      try {
        final BackendApi api =
            new FeatureFlagBackendApiFactory(config("agentless"), sco, EXPOSURE).create();
        if (canDeliver) {
          post(api);
        } else {
          assertThrows(IOException.class, () -> post(api));
        }
        assertEquals(canDeliver ? 2 : 1, server.getRequestCount());
        assertEquals("/info", server.takeRequest(1, TimeUnit.SECONDS).getPath());
        if (canDeliver) {
          final RecordedRequest event = server.takeRequest(1, TimeUnit.SECONDS);
          assertEquals("/evp_proxy/v4/api/v2/exposures", event.getPath());
          assertNull(event.getHeader("DD-API-KEY"));
          assertEquals("dd-trace-java", event.getHeader("DD-EVP-ORIGIN"));
          assertEquals(TracerVersion.TRACER_VERSION, event.getHeader("DD-EVP-ORIGIN-VERSION"));
        }
      } finally {
        sco.agentHttpClient.connectionPool().evictAll();
        sco.agentHttpClient.dispatcher().executorService().shutdownNow();
      }
    }
  }

  private static Config config(String source) {
    final Config config = mock(Config.class);
    when(config.getFeatureFlaggingConfigurationSource()).thenReturn(source);
    when(config.getIdGenerationStrategy()).thenReturn(Config.get().getIdGenerationStrategy());
    when(config.getProtocolVersion()).thenReturn(ProtocolVersion.V0_5);
    return config;
  }

  private static Response response(Request request, int code, String body) {
    return new Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("test")
        .body(ResponseBody.create(null, body))
        .build();
  }

  private static void post(BackendApi api) throws IOException {
    api.post(
        "exposures",
        RequestBody.create(MediaType.parse("application/json"), "{}"),
        stream -> null,
        null,
        false);
  }
}
