package com.datadog.featureflag;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import datadog.trace.api.Config;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UfcHttpConfigServiceTest {

  @Mock private FeatureFlaggingGateway.ConfigListener listener;

  @AfterEach
  void cleanup() {
    FeatureFlaggingGateway.removeConfigListener(listener);
    FeatureFlaggingGateway.dispatch((ServerConfiguration) null);
  }

  @Test
  void appendsMockConfigPathWhenConfiguredUrlIsRoot() {
    final Config config = config("http://mock-backend:8092", "datadoghq.com", "");

    assertEquals(
        "http://mock-backend:8092/mock/ufc/config",
        UfcHttpConfigService.endpoint(config).toString());
  }

  @Test
  void preservesConfiguredFullEndpointUrl() {
    final Config config =
        config("http://mock-backend:8092/mock/ufc/config?fixture=valid", "datadoghq.com", "");

    assertEquals(
        "http://mock-backend:8092/mock/ufc/config?fixture=valid",
        UfcHttpConfigService.endpoint(config).toString());
  }

  @Test
  void derivesDefaultEndpointFromSiteAndEnv() {
    final Config config = config(null, "datad0g.com", "staging env");

    assertEquals(
        "https://api.datad0g.com/api/v2/feature-flagging/config/server-distribution?dd_env=staging+env",
        UfcHttpConfigService.endpoint(config).toString());
  }

  @Test
  void appliesAcceptedUfcThroughGatewayAndSendsHeaders() throws Exception {
    final FakeClient client = new FakeClient(response(200, "etag-a", emptyConfig()));
    final UfcHttpConfigService service = service(client, extraHeaders());
    FeatureFlaggingGateway.addConfigListener(listener);

    assertTrue(service.pollOnce());

    verify(listener).accept(any(ServerConfiguration.class));
    assertEquals("test-api-key", client.requests.get(0).apiKey);
    assertEquals("app-key", client.requests.get(0).extraHeaders.get("DD-APPLICATION-KEY"));
    assertEquals("1", client.requests.get(0).extraHeaders.get("fastly-client"));
    assertNull(client.requests.get(0).etag);
  }

  @Test
  void usesEtagAndSkipsDispatchOnUnchangedConfig() throws Exception {
    final FakeClient client =
        new FakeClient(response(200, "etag-a", emptyConfig()), response(304, null, null));
    final UfcHttpConfigService service = service(client, extraHeaders());
    FeatureFlaggingGateway.addConfigListener(listener);

    assertTrue(service.pollOnce());
    assertTrue(service.pollOnce());

    verify(listener).accept(any(ServerConfiguration.class));
    assertEquals("etag-a", client.requests.get(1).etag);
  }

  @Test
  void keepsLastKnownGoodOnAuthFailureAndMalformedPayload() throws Exception {
    final FakeClient client =
        new FakeClient(
            response(401, null, null),
            response(200, null, "{not-json}"),
            response(200, null, "{\"flags\":[]}"));
    final UfcHttpConfigService service = service(client, extraHeaders());
    FeatureFlaggingGateway.addConfigListener(listener);

    assertFalse(service.pollOnce());
    assertFalse(service.pollOnce());
    assertFalse(service.pollOnce());

    verifyNoInteractions(listener);
  }

  @Test
  void retriesTimeoutBeforeApplyingConfig() throws Exception {
    final FakeClient client =
        new FakeClient(
            new SocketTimeoutException("slow HTTP configuration source"),
            new SocketTimeoutException("slow HTTP configuration source"),
            response(200, "etag-a", emptyConfig()));
    final UfcHttpConfigService service = service(client, extraHeaders());
    FeatureFlaggingGateway.addConfigListener(listener);

    assertTrue(service.pollOnce());

    assertEquals(3, client.calls.get());
    verify(listener).accept(any(ServerConfiguration.class));
  }

  @Test
  void retriesServerErrorThenKeepsColdStateOnNotModified() throws Exception {
    final FakeClient client = new FakeClient(response(500, null, null), response(304, null, null));
    final UfcHttpConfigService service = service(client, extraHeaders());
    FeatureFlaggingGateway.addConfigListener(listener);

    assertTrue(service.pollOnce());

    assertEquals(2, client.calls.get());
    verifyNoInteractions(listener);
  }

  @Test
  void rejectsOverlappingPolls() throws Exception {
    final CountDownLatch requestStarted = new CountDownLatch(1);
    final CountDownLatch releaseRequest = new CountDownLatch(1);
    final FakeClient client = new FakeClient(response(200, "etag-a", emptyConfig()));
    client.block(requestStarted, releaseRequest);
    final UfcHttpConfigService service = service(client, extraHeaders());
    final ExecutorService runner = Executors.newFixedThreadPool(2);

    try {
      final Future<Boolean> first = runner.submit(service::pollOnce);
      assertTrue(requestStarted.await(1, TimeUnit.SECONDS));
      final Future<Boolean> second = runner.submit(service::pollOnce);

      assertFalse(second.get(1, TimeUnit.SECONDS));
      releaseRequest.countDown();
      assertTrue(first.get(1, TimeUnit.SECONDS));
      assertEquals(1, client.calls.get());
    } finally {
      releaseRequest.countDown();
      runner.shutdownNow();
    }
  }

  @Test
  void closePreventsFurtherPolls() throws Exception {
    final FakeClient client = new FakeClient(response(200, "etag-a", emptyConfig()));
    final UfcHttpConfigService service = service(client, extraHeaders());

    service.close();

    assertFalse(service.pollOnce());
    assertEquals(0, client.calls.get());
  }

  private static UfcHttpConfigService service(
      final FakeClient client, final Map<String, String> extraHeaders) {
    final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    return new UfcHttpConfigService(
        HttpUrl.get("http://localhost/mock/ufc/config"),
        config("http://localhost", "datadoghq.com", "", extraHeaders),
        60_000,
        client,
        executor);
  }

  private static Config config(final String baseUrl, final String site, final String env) {
    return config(baseUrl, site, env, new HashMap<>());
  }

  private static Config config(
      final String baseUrl,
      final String site,
      final String env,
      final Map<String, String> extraHeaders) {
    final Config config = mock(Config.class);
    lenient().when(config.getFlaggingConfigurationSourceBaseUrl()).thenReturn(baseUrl);
    lenient().when(config.getFlaggingConfigurationSourceExtraHeaders()).thenReturn(extraHeaders);
    lenient().when(config.getFlaggingConfigurationSourcePollIntervalSeconds()).thenReturn(30.0D);
    lenient().when(config.getFlaggingConfigurationSourceRequestTimeoutSeconds()).thenReturn(2.0D);
    lenient().when(config.getApiKey()).thenReturn("test-api-key");
    lenient().when(config.getSite()).thenReturn(site);
    lenient().when(config.getEnv()).thenReturn(env);
    return config;
  }

  private static Map<String, String> extraHeaders() {
    final Map<String, String> headers = new HashMap<>();
    headers.put("DD-APPLICATION-KEY", "app-key");
    headers.put("fastly-client", "1");
    return headers;
  }

  private static UfcHttpConfigService.UfcHttpResponse response(
      final int status, final String etag, final String body) {
    return new UfcHttpConfigService.UfcHttpResponse(
        status, etag, body == null ? null : body.getBytes(UTF_8));
  }

  private static String emptyConfig() {
    return "{"
        + "\"createdAt\":\"2024-04-17T19:40:53.716Z\","
        + "\"format\":\"SERVER\","
        + "\"environment\":{\"name\":\"Test\"},"
        + "\"flags\":{}"
        + "}";
  }

  private static final class FakeClient implements UfcHttpConfigService.UfcHttpClient {
    private final AtomicInteger calls = new AtomicInteger();
    private final List<Request> requests = new ArrayList<>();
    private final BlockingQueue<Object> responses = new LinkedBlockingQueue<>();
    private CountDownLatch requestStarted;
    private CountDownLatch releaseRequest;

    private FakeClient(final Object... responses) {
      for (final Object response : responses) {
        this.responses.add(response);
      }
    }

    private void block(final CountDownLatch requestStarted, final CountDownLatch releaseRequest) {
      this.requestStarted = requestStarted;
      this.releaseRequest = releaseRequest;
    }

    @Override
    public UfcHttpConfigService.UfcHttpResponse fetch(
        final HttpUrl endpoint,
        final Config config,
        final Map<String, String> extraHeaders,
        final String etag)
        throws IOException {
      calls.incrementAndGet();
      requests.add(new Request(config.getApiKey(), etag, extraHeaders));
      if (requestStarted != null) {
        requestStarted.countDown();
      }
      if (releaseRequest != null) {
        await(releaseRequest);
      }
      final Object response = responses.remove();
      if (response instanceof IOException) {
        throw (IOException) response;
      }
      return (UfcHttpConfigService.UfcHttpResponse) response;
    }

    private static void await(final CountDownLatch latch) throws IOException {
      try {
        if (!latch.await(1, TimeUnit.SECONDS)) {
          throw new SocketTimeoutException("test request did not release");
        }
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException(e);
      }
    }
  }

  private static final class Request {
    private final String apiKey;
    private final String etag;
    private final Map<String, String> extraHeaders;

    private Request(
        final String apiKey, final String etag, final Map<String, String> extraHeaders) {
      this.apiKey = apiKey;
      this.etag = etag;
      this.extraHeaders = new HashMap<>(extraHeaders);
    }
  }
}
