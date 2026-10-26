package com.datadog.featureflag;

import static com.datadog.featureflag.FeatureFlagEventType.EXPOSURE;
import static com.datadog.featureflag.FeatureFlagEventType.FLAG_EVALUATION;
import static datadog.communication.ddagent.DDAgentFeaturesDiscovery.V2_EVP_PROXY_ENDPOINT;
import static datadog.communication.ddagent.DDAgentFeaturesDiscovery.V4_EVP_PROXY_ENDPOINT;
import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.CONFIGURATION_SOURCE_AGENTLESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.communication.BackendApi;
import datadog.communication.HttpResponseException;
import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;

/** Exercises both production factories and HTTP clients against one shared discovery result. */
class SharedFeatureFlagRouteTest {
  private final AtomicLong clock = new AtomicLong();
  private final FeatureFlagRouteSelector selector = new FeatureFlagRouteSelector(clock::get, 10);
  private final DDAgentFeaturesDiscovery discovery = mock(DDAgentFeaturesDiscovery.class);
  private final Config config = mock(Config.class);
  private final SharedCommunicationObjects sco = new SharedCommunicationObjects();
  private final List<Request> requests = new ArrayList<>();
  private int status = 200;

  SharedFeatureFlagRouteTest() {
    when(config.getFeatureFlaggingConfigurationSource()).thenReturn(CONFIGURATION_SOURCE_AGENTLESS);
    when(config.getIdGenerationStrategy()).thenReturn(Config.get().getIdGenerationStrategy());
    when(discovery.supportsEvpProxyHeaders(any())).thenReturn(true);
    sco.setFeaturesDiscovery(discovery);
    sco.agentUrl = HttpUrl.get("http://localhost:8126/agent-prefix/");
    sco.agentHttpClient =
        new OkHttpClient.Builder()
            .addInterceptor(
                chain -> {
                  requests.add(chain.request());
                  return new Response.Builder()
                      .request(chain.request())
                      .protocol(Protocol.HTTP_1_1)
                      .code(status)
                      .message("test")
                      .body(ResponseBody.create(null, "{}"))
                      .build();
                })
            .build();
  }

  @Test
  void bothWritersUseOneSuccessfulRecoveryWithoutRediscovery() throws Exception {
    final BackendApi exposures = create(EXPOSURE);
    final BackendApi evaluations = create(FLAG_EVALUATION);
    // Only the first recovery can discover a route. A redundant sibling probe would lose it.
    when(discovery.getEvpProxyEndpoint()).thenReturn(V4_EVP_PROXY_ENDPOINT).thenReturn(null);
    clock.set(10);

    post(exposures, "exposures");
    post(evaluations, "flagevaluation");

    assertEquals(
        Arrays.asList(
            "/agent-prefix/evp_proxy/v4/api/v2/exposures",
            "/agent-prefix/evp_proxy/v4/api/v2/flagevaluation"),
        paths());
    assertEquals("gzip", requests.get(0).header("Accept-Encoding"));
    assertEquals("identity", requests.get(1).header("Accept-Encoding"));
    verify(discovery).discover();
    verify(discovery).discoverIfOutdated();
  }

  @Test
  void bothWritersReplaceTheirOldEndpointAfterRecovery() throws Exception {
    when(discovery.getEvpProxyEndpoint()).thenReturn(V4_EVP_PROXY_ENDPOINT);
    final BackendApi exposures = create(EXPOSURE);
    final BackendApi evaluations = create(FLAG_EVALUATION);
    post(evaluations, "flagevaluation");
    status = 404;
    assertThrows(HttpResponseException.class, () -> post(exposures, "exposures"));
    when(discovery.getEvpProxyEndpoint()).thenReturn(V2_EVP_PROXY_ENDPOINT);
    status = 200;
    clock.set(10);

    post(exposures, "exposures");
    post(evaluations, "flagevaluation");

    assertEquals(
        Arrays.asList(
            "/agent-prefix/evp_proxy/v4/api/v2/flagevaluation",
            "/agent-prefix/evp_proxy/v4/api/v2/exposures",
            "/agent-prefix/evp_proxy/v2/api/v2/exposures",
            "/agent-prefix/evp_proxy/v2/api/v2/flagevaluation"),
        paths());
    verify(discovery, times(1)).discover();
  }

  private BackendApi create(FeatureFlagEventType eventType) {
    return new FeatureFlagBackendApiFactory(config, sco, eventType, selector).create();
  }

  @Test
  void concurrentInitializationSharesOneDiscovery() throws Exception {
    final CountDownLatch started = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    when(discovery.getEvpProxyEndpoint()).thenReturn(V4_EVP_PROXY_ENDPOINT);
    doAnswer(
            invocation -> {
              started.countDown();
              assertTrue(release.await(5, TimeUnit.SECONDS));
              return null;
            })
        .when(discovery)
        .discoverIfOutdated();
    final CompletableFuture<BackendApi> exposures =
        CompletableFuture.supplyAsync(() -> create(EXPOSURE));
    try {
      assertTrue(started.await(5, TimeUnit.SECONDS));
      final CompletableFuture<BackendApi> evaluations =
          CompletableFuture.supplyAsync(() -> create(FLAG_EVALUATION));
      release.countDown();
      post(exposures.get(5, TimeUnit.SECONDS), "exposures");
      post(evaluations.get(5, TimeUnit.SECONDS), "flagevaluation");
      verify(discovery).discoverIfOutdated();
      assertEquals(2, requests.size());
    } finally {
      release.countDown();
    }
  }

  @Test
  void slowRecoveryDoesNotPermitASecondProbeAfterTheCooldown() throws Exception {
    final BackendApi exposures = create(EXPOSURE);
    final BackendApi evaluations = create(FLAG_EVALUATION);
    final CountDownLatch started = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    when(discovery.getEvpProxyEndpoint()).thenReturn(V4_EVP_PROXY_ENDPOINT);
    doAnswer(
            invocation -> {
              started.countDown();
              assertTrue(release.await(5, TimeUnit.SECONDS));
              return null;
            })
        .when(discovery)
        .discover();
    clock.set(10);
    final CompletableFuture<Void> recovery =
        CompletableFuture.runAsync(
            () -> {
              try {
                post(exposures, "exposures");
              } catch (Exception failure) {
                throw new AssertionError(failure);
              }
            });
    try {
      assertTrue(started.await(5, TimeUnit.SECONDS));
      clock.set(100);
      assertThrows(IOException.class, () -> post(evaluations, "flagevaluation"));
    } finally {
      release.countDown();
    }
    recovery.get(5, TimeUnit.SECONDS);
    post(evaluations, "flagevaluation");
    verify(discovery).discover();
    assertEquals(2, requests.size());
  }

  private List<String> paths() {
    final List<String> paths = new ArrayList<>();
    for (Request request : requests) {
      paths.add(request.url().encodedPath());
    }
    return paths;
  }

  private static void post(BackendApi api, String path) throws Exception {
    api.post(
        path,
        RequestBody.create(MediaType.parse("application/json"), "{}"),
        stream -> null,
        null,
        false);
  }
}
