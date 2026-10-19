package com.datadog.featureflag;

import static datadog.communication.ddagent.DDAgentFeaturesDiscovery.V4_EVP_PROXY_ENDPOINT;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectSysConfig;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.exposure.Allocation;
import datadog.trace.api.featureflag.exposure.ExposureEvent;
import datadog.trace.api.featureflag.exposure.Flag;
import datadog.trace.api.featureflag.exposure.Subject;
import datadog.trace.api.featureflag.exposure.Variant;
import datadog.trace.api.featureflag.flagevaluation.FlagEvalEvent;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class FeatureFlagRouteWiringTest {
  @Test
  @Timeout(15)
  @WithConfig(key = "feature.flags.configuration.source", value = "agentless")
  @WithConfig(key = "API_KEY", value = "test-api-key", env = true)
  @WithConfig(key = "site", value = "datadoghq.com")
  void productionWritersShareFallbackForFutureBatches() throws Exception {
    final HttpServer configurationServer =
        HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    configurationServer.createContext(
        "/",
        exchange -> {
          exchange.sendResponseHeaders(404, -1);
          exchange.close();
        });
    configurationServer.start();
    injectSysConfig(
        "feature.flags.configuration.source.agentless.base.url",
        "http://127.0.0.1:" + configurationServer.getAddress().getPort() + "/config");
    final List<Request> requests = new CopyOnWriteArrayList<>();
    final CountDownLatch exposureDelivered = new CountDownLatch(1);
    final OkHttpClient client =
        new OkHttpClient.Builder()
            .addInterceptor(
                chain -> {
                  final Request request = chain.request();
                  requests.add(request);
                  final boolean direct =
                      request.url().host().equals("event-platform-intake.datadoghq.com");
                  if (direct && request.url().encodedPath().equals("/api/v2/exposures")) {
                    exposureDelivered.countDown();
                  }
                  return new Response.Builder()
                      .request(request)
                      .protocol(Protocol.HTTP_1_1)
                      .code(direct ? 202 : 404)
                      .message("test")
                      .body(ResponseBody.create(null, "{}"))
                      .build();
                })
            .build();
    final DDAgentFeaturesDiscovery discovery = mock(DDAgentFeaturesDiscovery.class);
    when(discovery.supportsEvpProxyHeaders(any())).thenReturn(true);
    when(discovery.supportsEvpProxy()).thenReturn(true);
    when(discovery.getEvpProxyEndpoint()).thenReturn(V4_EVP_PROXY_ENDPOINT);
    final SharedCommunicationObjects sco =
        new SharedCommunicationObjects() {
          @Override
          public OkHttpClient getIntakeHttpClient() {
            return client;
          }

          @Override
          public DDAgentFeaturesDiscovery featuresDiscovery(Config config) {
            return discovery;
          }
        };
    sco.agentUrl = HttpUrl.get("http://localhost:8126/");
    sco.agentHttpClient = client;
    // A terminal local configuration response avoids retry backoff before event activation.
    try {
      FeatureFlaggingSystem.start(sco);
      FeatureFlaggingGateway.activate();
      FeatureFlaggingGateway.dispatch(
          new ExposureEvent(
              1,
              new Allocation("allocation"),
              new Flag("flag"),
              new Variant("on"),
              new Subject("user", emptyMap())));
      assertTrue(exposureDelivered.await(5, TimeUnit.SECONDS));
      FeatureFlaggingGateway.getFlagEvalWriter()
          .enqueue(new FlagEvalEvent("flag", "on", "allocation", "user", 1, emptyMap()));
      FeatureFlaggingSystem.stop();

      assertEquals(3, requests.size(), "One rejected local exposure, then two direct batches");
      assertEquals("/evp_proxy/v4/api/v2/exposures", requests.get(0).url().encodedPath());
      assertNull(requests.get(0).header("DD-API-KEY"));
      assertEquals("/api/v2/exposures", requests.get(1).url().encodedPath());
      assertEquals("/api/v2/flagevaluation", requests.get(2).url().encodedPath());
      for (int i = 1; i < requests.size(); i++) {
        assertEquals("event-platform-intake.datadoghq.com", requests.get(i).url().host());
        assertEquals("test-api-key", requests.get(i).header("DD-API-KEY"));
        assertNull(requests.get(i).header("X-Datadog-EVP-Subdomain"));
      }
    } finally {
      FeatureFlaggingSystem.stop();
      FeatureFlaggingGateway.setFlagEvaluationEnqueueEnabled(true);
      client.connectionPool().evictAll();
      client.dispatcher().executorService().shutdownNow();
      configurationServer.stop(0);
    }
  }
}
