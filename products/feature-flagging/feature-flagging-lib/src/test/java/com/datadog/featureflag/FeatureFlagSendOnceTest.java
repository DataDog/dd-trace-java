package com.datadog.featureflag;

import static datadog.communication.ddagent.DDAgentFeaturesDiscovery.V4_EVP_PROXY_ENDPOINT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.communication.BackendApi;
import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.Timeout;
import org.tabletest.junit.TableTest;

@Timeout(15)
class FeatureFlagSendOnceTest {
  @TableTest({
    "Source        | Route  | Event          ",
    "remote_config | proxy  | EXPOSURE       ",
    "remote_config | proxy  | FLAG_EVALUATION",
    "agentless     | proxy  | EXPOSURE       ",
    "agentless     | proxy  | FLAG_EVALUATION",
    "agentless     | direct | EXPOSURE       ",
    "agentless     | direct | FLAG_EVALUATION"
  })
  void productionFactoryDoesNotReplayAnAcceptedPostOnConnectionLoss(
      String source, String route, FeatureFlagEventType event) throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      server.enqueue(new MockResponse().setBody("{}"));
      server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
      server.enqueue(new MockResponse().setBody("{}"));
      final OkHttpClient client = localClient(server);
      try {
        final BackendApi api = api(server, client, source, route, event);
        post(api);
        assertThrows(IOException.class, () -> post(api));
        assertEquals(2, server.getRequestCount());
        for (int i = 0; i < 2; i++) {
          final RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
          assertEquals("POST", request.getMethod());
          if (route.equals("proxy")) {
            assertNull(request.getHeader("DD-API-KEY"));
          } else {
            assertEquals("test-api-key", request.getHeader("DD-API-KEY"));
            assertNull(request.getHeader("X-Datadog-EVP-Subdomain"));
          }
        }
      } finally {
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdownNow();
      }
    }
  }

  @TableTest({
    "Source        | Route ",
    "remote_config | proxy ",
    "agentless     | proxy ",
    "agentless     | direct"
  })
  void redirectDoesNotForwardTheBatchOrCredentials(String source, String route) throws Exception {
    try (MockWebServer server = new MockWebServer();
        MockWebServer destination = new MockWebServer()) {
      server.start();
      destination.start();
      server.enqueue(
          new MockResponse()
              .setResponseCode(307)
              .addHeader("Location", destination.url("/redirected")));
      destination.enqueue(new MockResponse().setBody("{}"));
      final OkHttpClient client = localClient(server);
      try {
        final BackendApi api = api(server, client, source, route, FeatureFlagEventType.EXPOSURE);
        assertThrows(IOException.class, () -> post(api));
        assertEquals(1, server.getRequestCount());
        assertNull(destination.takeRequest(200, TimeUnit.MILLISECONDS));
      } finally {
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdownNow();
      }
    }
  }

  private static OkHttpClient localClient(MockWebServer server) {
    return new OkHttpClient.Builder()
        .addInterceptor(
            chain -> {
              final Request original = chain.request();
              final HttpUrl local =
                  original
                      .url()
                      .newBuilder()
                      .scheme("http")
                      .host(server.getHostName())
                      .port(server.getPort())
                      .build();
              return chain.proceed(original.newBuilder().url(local).build());
            })
        .build();
  }

  private static BackendApi api(
      MockWebServer server,
      OkHttpClient client,
      String source,
      String route,
      FeatureFlagEventType event) {
    final Config config = mock(Config.class);
    when(config.getIdGenerationStrategy()).thenReturn(Config.get().getIdGenerationStrategy());
    when(config.getFeatureFlaggingConfigurationSource()).thenReturn(source);
    when(config.getSite()).thenReturn("datadoghq.com");
    if (route.equals("direct")) {
      when(config.getApiKey()).thenReturn("test-api-key");
    }
    final DDAgentFeaturesDiscovery discovery = mock(DDAgentFeaturesDiscovery.class);
    when(discovery.supportsEvpProxyHeaders(any())).thenReturn(true);
    when(discovery.supportsEvpProxy()).thenReturn(route.equals("proxy"));
    when(discovery.getEvpProxyEndpoint())
        .thenReturn(route.equals("proxy") ? V4_EVP_PROXY_ENDPOINT : null);
    final SharedCommunicationObjects sco =
        new SharedCommunicationObjects() {
          @Override
          public OkHttpClient getIntakeHttpClient() {
            return client;
          }

          @Override
          public DDAgentFeaturesDiscovery featuresDiscovery(Config ignored) {
            return discovery;
          }
        };
    sco.agentUrl = server.url("/");
    sco.agentHttpClient = client;
    return new FeatureFlagBackendApiFactory(config, sco, event).create();
  }

  private static void post(BackendApi api) throws IOException {
    api.post(
        "events",
        RequestBody.create(MediaType.parse("application/json"), "{}"),
        stream -> null,
        null,
        false);
  }
}
