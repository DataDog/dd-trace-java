package com.datadog.featureflag;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import datadog.communication.http.OkHttpUtils;
import datadog.trace.agent.test.server.http.JavaTestHttpServer;
import datadog.trace.api.Config;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentlessConfigurationSourceTest {
  private static final String CONFIG_PATH = "/api/v2/feature-flagging/config/server-distribution";

  @Mock private FeatureFlaggingGateway.ConfigListener listener;

  @AfterEach
  void cleanup() {
    FeatureFlaggingGateway.removeConfigListener(listener);
    FeatureFlaggingGateway.dispatch((ServerConfiguration) null);
  }

  @Test
  void derivesDatadogApiServerDistributionEndpointFromSiteAndEnv() {
    final Config config = config("datad0g.com", "staging env");

    assertEquals(
        "https://api.datad0g.com/api/v2/feature-flagging/config/server-distribution?dd_env=staging+env",
        AgentlessConfigurationSource.endpoint(config).toString());
  }

  @Test
  void derivesDatadogApiServerDistributionEndpointWithoutEnv() {
    assertEquals(
        "https://api.datadoghq.com/api/v2/feature-flagging/config/server-distribution",
        AgentlessConfigurationSource.endpoint(config("datadoghq.com", "")).toString());
    assertEquals(
        "https://api.datadoghq.com/api/v2/feature-flagging/config/server-distribution",
        AgentlessConfigurationSource.endpoint(config("datadoghq.com", null)).toString());
  }

  @Test
  void rejectsInvalidDatadogApiServerDistributionEndpoint() {
    assertThrows(
        IllegalArgumentException.class,
        () -> AgentlessConfigurationSource.endpoint(config("datadoghq.com:bad", "")));
  }

  @Test
  void defaultConstructorBuildsHttpClientFromConfig() {
    final AgentlessConfigurationSource service =
        new AgentlessConfigurationSource(config("datad0g.com", "staging"));

    service.close();
  }

  @Test
  void realHttpClientSendsAgentlessHeadersAndReadsResponse() throws Exception {
    try (JavaTestHttpServer server =
        JavaTestHttpServer.httpServer(
            s ->
                s.handlers(
                    h ->
                        h.get(
                            CONFIG_PATH,
                            api ->
                                api.getResponse()
                                    .addHeader("ETag", "etag-b")
                                    .send(emptyConfig()))))) {
      final OkHttpClient httpClient = new OkHttpClient.Builder().build();
      final AgentlessConfigurationSource.OkHttpUfcHttpClient client =
          new AgentlessConfigurationSource.OkHttpUfcHttpClient(httpClient);

      try {
        final AgentlessConfigurationSource.UfcHttpResponse response =
            client.fetch(HttpUrl.get(server.getAddress().resolve(CONFIG_PATH)), config(), "etag-a");

        assertEquals(HttpURLConnection.HTTP_OK, response.status);
        assertEquals("etag-b", response.etag);
        assertEquals(emptyConfig(), new String(response.body, UTF_8));
        assertEquals("test-api-key", server.getLastRequest().getHeader("DD-API-KEY"));
        assertEquals("etag-a", server.getLastRequest().getHeader("If-None-Match"));
        assertEquals("java", server.getLastRequest().getHeader("Datadog-Meta-Lang"));
      } finally {
        httpClient.dispatcher().executorService().shutdownNow();
        httpClient.connectionPool().evictAll();
      }
    }
  }

  @Test
  void realHttpClientAllowsMissingEtagAndEmptyResponseBody() throws Exception {
    try (JavaTestHttpServer server =
        JavaTestHttpServer.httpServer(
            s ->
                s.handlers(
                    h ->
                        h.get(
                            CONFIG_PATH,
                            api ->
                                api.getResponse()
                                    .status(HttpURLConnection.HTTP_NO_CONTENT)
                                    .send())))) {
      final OkHttpClient httpClient = new OkHttpClient.Builder().build();
      final AgentlessConfigurationSource.OkHttpUfcHttpClient client =
          new AgentlessConfigurationSource.OkHttpUfcHttpClient(httpClient);

      try {
        final AgentlessConfigurationSource.UfcHttpResponse response =
            client.fetch(HttpUrl.get(server.getAddress().resolve(CONFIG_PATH)), config(), null);

        assertEquals(HttpURLConnection.HTTP_NO_CONTENT, response.status);
        assertNull(response.etag);
        assertEquals(0, response.body.length);
        assertNull(server.getLastRequest().getHeader("If-None-Match"));
      } finally {
        httpClient.dispatcher().executorService().shutdownNow();
        httpClient.connectionPool().evictAll();
      }
    }
  }

  @Test
  void realHttpClientCancellationInterruptsInFlightRequest() throws Exception {
    final CountDownLatch requestStarted = new CountDownLatch(1);
    final CountDownLatch releaseRequest = new CountDownLatch(1);
    try (JavaTestHttpServer server =
        JavaTestHttpServer.httpServer(
            s ->
                s.handlers(
                    h ->
                        h.get(
                            CONFIG_PATH,
                            api -> {
                              requestStarted.countDown();
                              assertTrue(releaseRequest.await(1, TimeUnit.SECONDS));
                              api.getResponse().send(emptyConfig());
                            })))) {
      final OkHttpClient httpClient = new OkHttpClient.Builder().build();
      final AgentlessConfigurationSource.OkHttpUfcHttpClient client =
          new AgentlessConfigurationSource.OkHttpUfcHttpClient(httpClient);
      final ExecutorService runner = Executors.newSingleThreadExecutor();

      try {
        final Future<AgentlessConfigurationSource.UfcHttpResponse> response =
            runner.submit(
                () ->
                    client.fetch(
                        HttpUrl.get(server.getAddress().resolve(CONFIG_PATH)), config(), null));
        assertTrue(requestStarted.await(1, TimeUnit.SECONDS));
        assertThrows(
            IllegalStateException.class,
            () ->
                client.fetch(
                    HttpUrl.get(server.getAddress().resolve(CONFIG_PATH)), config(), null));

        client.cancel();

        final ExecutionException failure =
            assertThrows(ExecutionException.class, () -> response.get(1, TimeUnit.SECONDS));
        assertInstanceOf(IOException.class, failure.getCause());
      } finally {
        releaseRequest.countDown();
        runner.shutdownNow();
        httpClient.dispatcher().executorService().shutdownNow();
        httpClient.connectionPool().evictAll();
      }
    }
  }

  @Test
  void realHttpClientCancellationBeforeFetchPreventsRequest() throws Exception {
    try (JavaTestHttpServer server =
        JavaTestHttpServer.httpServer(
            s ->
                s.handlers(
                    h -> h.get(CONFIG_PATH, api -> api.getResponse().send(emptyConfig()))))) {
      final OkHttpClient httpClient = new OkHttpClient.Builder().build();
      final AgentlessConfigurationSource.OkHttpUfcHttpClient client =
          new AgentlessConfigurationSource.OkHttpUfcHttpClient(httpClient);

      try {
        client.cancel();

        assertThrows(
            IOException.class,
            () ->
                client.fetch(
                    HttpUrl.get(server.getAddress().resolve(CONFIG_PATH)), config(), null));
      } finally {
        httpClient.dispatcher().executorService().shutdownNow();
        httpClient.connectionPool().evictAll();
      }
    }
  }

  @Test
  void realHttpClientTimesOutDelayedResponse() throws Exception {
    try (JavaTestHttpServer server =
        JavaTestHttpServer.httpServer(
            s ->
                s.handlers(
                    h ->
                        h.get(
                            CONFIG_PATH,
                            api -> {
                              TimeUnit.MILLISECONDS.sleep(500);
                              api.getResponse().send(emptyConfig());
                            })))) {
      final HttpUrl endpoint = HttpUrl.get(server.getAddress().resolve(CONFIG_PATH));
      final OkHttpClient httpClient = OkHttpUtils.buildHttpClient(endpoint, 50);
      final AgentlessConfigurationSource.OkHttpUfcHttpClient client =
          new AgentlessConfigurationSource.OkHttpUfcHttpClient(httpClient);

      try {
        assertThrows(IOException.class, () -> client.fetch(endpoint, config(), null));
      } finally {
        httpClient.dispatcher().executorService().shutdownNow();
        httpClient.connectionPool().evictAll();
      }
    }
  }

  @Test
  void appliesAcceptedUfcThroughGatewayAndSendsApiKey() throws Exception {
    final FakeClient client = new FakeClient(response(200, "etag-a", emptyConfig()));
    final AgentlessConfigurationSource service = service(client);
    FeatureFlaggingGateway.addConfigListener(listener);

    assertTrue(service.pollOnce());

    verify(listener).accept(any(ServerConfiguration.class));
    assertEquals("test-api-key", client.requests.get(0).apiKey);
    assertNull(client.requests.get(0).etag);
  }

  @Test
  void ignoresBlankEtag() throws Exception {
    final FakeClient client =
        new FakeClient(response(200, " ", emptyConfig()), response(304, null, null));
    final AgentlessConfigurationSource service = service(client);
    FeatureFlaggingGateway.addConfigListener(listener);

    assertTrue(service.pollOnce());
    assertTrue(service.pollOnce());

    assertNull(client.requests.get(1).etag);
    verify(listener).accept(any(ServerConfiguration.class));
  }

  @Test
  void usesEtagAndSkipsDispatchOnUnchangedConfig() throws Exception {
    final FakeClient client =
        new FakeClient(response(200, "etag-a", emptyConfig()), response(304, null, null));
    final AgentlessConfigurationSource service = service(client);
    FeatureFlaggingGateway.addConfigListener(listener);

    assertTrue(service.pollOnce());
    assertTrue(service.pollOnce());

    verify(listener).accept(any(ServerConfiguration.class));
    assertEquals("etag-a", client.requests.get(1).etag);
  }

  @Test
  void coldNotModifiedDoesNotEstablishEtag() throws Exception {
    final FakeClient client =
        new FakeClient(response(304, "etag-cold", null), response(200, "etag-warm", emptyConfig()));
    final AgentlessConfigurationSource service = service(client);
    FeatureFlaggingGateway.addConfigListener(listener);

    assertTrue(service.pollOnce());
    assertTrue(service.pollOnce());

    assertNull(client.requests.get(1).etag);
    verify(listener).accept(any(ServerConfiguration.class));
  }

  @Test
  void failedGatewayDispatchDoesNotAdvanceEtag() throws Exception {
    final FakeClient client =
        new FakeClient(
            response(200, "etag-a", emptyConfig()), response(200, "etag-b", emptyConfig()));
    final AgentlessConfigurationSource service = service(client);
    final FeatureFlaggingGateway.ConfigListener failingListener =
        configuration -> {
          throw new IllegalStateException("listener rejected configuration");
        };
    FeatureFlaggingGateway.addConfigListener(failingListener);

    try {
      assertThrows(IllegalStateException.class, service::pollOnce);
      FeatureFlaggingGateway.removeConfigListener(failingListener);

      assertTrue(service.pollOnce());

      assertNull(client.requests.get(1).etag);
    } finally {
      FeatureFlaggingGateway.removeConfigListener(failingListener);
    }
  }

  @Test
  void keepsLastKnownGoodOnAuthFailureAndMalformedPayload() throws Exception {
    final FakeClient client =
        new FakeClient(
            response(200, "etag-good", emptyConfig()),
            response(401, null, null),
            response(200, null, "{not-json}"),
            response(200, null, "{\"flags\":[]}"));
    final AgentlessConfigurationSource service = service(client);
    FeatureFlaggingGateway.addConfigListener(listener);

    assertTrue(service.pollOnce());
    assertFalse(service.pollOnce());
    assertFalse(service.pollOnce());
    assertFalse(service.pollOnce());

    verify(listener).accept(any(ServerConfiguration.class));
    assertEquals("etag-good", client.requests.get(1).etag);
    assertEquals("etag-good", client.requests.get(2).etag);
    assertEquals("etag-good", client.requests.get(3).etag);
  }

  @Test
  void rejectsForbiddenNonOkMissingBodyAndNullConfiguration() throws Exception {
    final FakeClient client =
        new FakeClient(
            response(403, null, null),
            response(404, null, null),
            response(600, null, null),
            response(200, null, null),
            response(200, null, "null"));
    final AgentlessConfigurationSource service = service(client);
    FeatureFlaggingGateway.addConfigListener(listener);

    assertFalse(service.pollOnce());
    assertFalse(service.pollOnce());
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
    final AgentlessConfigurationSource service = service(client);
    FeatureFlaggingGateway.addConfigListener(listener);

    assertTrue(service.pollOnce());

    assertEquals(3, client.calls.get());
    verify(listener).accept(any(ServerConfiguration.class));
  }

  @Test
  void retriesClientTimeoutAndRateLimitStatusBeforeApplyingConfig() throws Exception {
    final FakeClient client =
        new FakeClient(
            response(408, null, null),
            response(200, "etag-a", emptyConfig()),
            response(429, null, null),
            response(200, "etag-b", emptyConfig()));
    final AgentlessConfigurationSource service = service(client);
    FeatureFlaggingGateway.addConfigListener(listener);

    assertTrue(service.pollOnce());
    assertTrue(service.pollOnce());

    assertEquals(4, client.calls.get());
    verify(listener, times(2)).accept(any(ServerConfiguration.class));
  }

  @Test
  void retriesServerErrorThenKeepsColdStateOnNotModified() throws Exception {
    final FakeClient client = new FakeClient(response(500, null, null), response(304, null, null));
    final AgentlessConfigurationSource service = service(client);
    FeatureFlaggingGateway.addConfigListener(listener);

    assertTrue(service.pollOnce());

    assertEquals(2, client.calls.get());
    verifyNoInteractions(listener);
  }

  @Test
  void givesUpAfterRetryableFailuresAreExhausted() throws Exception {
    final FakeClient client =
        new FakeClient(
            response(503, null, null), response(503, null, null), response(503, null, null));
    final AgentlessConfigurationSource service = service(client);
    FeatureFlaggingGateway.addConfigListener(listener);

    assertFalse(service.pollOnce());

    assertEquals(3, client.calls.get());
    verifyNoInteractions(listener);
  }

  @Test
  void givesUpAfterIoFailuresAreExhausted() throws Exception {
    final FakeClient client =
        new FakeClient(
            new SocketTimeoutException("slow HTTP configuration source"),
            new SocketTimeoutException("slow HTTP configuration source"),
            new SocketTimeoutException("slow HTTP configuration source"));
    final AgentlessConfigurationSource service = service(client);
    FeatureFlaggingGateway.addConfigListener(listener);

    assertFalse(service.pollOnce());

    assertEquals(3, client.calls.get());
    verifyNoInteractions(listener);
  }

  @Test
  void usesIntervalAwareRetryBackoff() throws Exception {
    final List<Long> delays = new ArrayList<>();
    final FakeClient client =
        new FakeClient(
            response(503, null, null),
            new SocketTimeoutException("slow HTTP configuration source"),
            response(200, "etag-a", emptyConfig()));
    final AgentlessConfigurationSource service = service(client, delays::add, () -> 1.0);
    FeatureFlaggingGateway.addConfigListener(listener);

    assertTrue(service.pollOnce());

    assertEquals(java.util.Arrays.asList(5_000L, 10_000L), delays);
    verify(listener).accept(any(ServerConfiguration.class));
  }

  @Test
  void clampsAndJittersRetryBackoff() {
    assertEquals(2_000, AgentlessConfigurationSource.retryDelayMillis(1_000, 1, 1.0));
    assertEquals(5_000, AgentlessConfigurationSource.retryDelayMillis(1_000, 2, 1.0));
    assertEquals(10_000, AgentlessConfigurationSource.retryDelayMillis(600_000, 1, 1.0));
    assertEquals(30_000, AgentlessConfigurationSource.retryDelayMillis(600_000, 2, 1.0));
    assertEquals(6_000, AgentlessConfigurationSource.retryDelayMillis(30_000, 1, 1.2));
    assertThrows(
        IllegalArgumentException.class,
        () -> AgentlessConfigurationSource.retryDelayMillis(30_000, 3, 1.0));
  }

  @Test
  void rejectsOverlappingPolls() throws Exception {
    final CountDownLatch requestStarted = new CountDownLatch(1);
    final CountDownLatch releaseRequest = new CountDownLatch(1);
    final FakeClient client = new FakeClient(response(200, "etag-a", emptyConfig()));
    client.block(requestStarted, releaseRequest);
    final AgentlessConfigurationSource service = service(client);
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
  void initSchedulesPollAndCloseCancelsFuture() throws Exception {
    final FakeClient client = new FakeClient(response(200, "etag-a", emptyConfig()));
    final AgentlessConfigurationSource service =
        new AgentlessConfigurationSource(
            HttpUrl.get("http://localhost" + CONFIG_PATH),
            config(),
            60_000,
            client,
            Executors.newSingleThreadScheduledExecutor());
    FeatureFlaggingGateway.addConfigListener(listener);

    service.init();
    awaitCalls(client, 1);
    service.close();

    verify(listener).accept(any(ServerConfiguration.class));
  }

  @Test
  void repeatedInitStartsOnlyOnePoller() throws Exception {
    final FakeClient client = new FakeClient(response(200, "etag-a", emptyConfig()));
    final AgentlessConfigurationSource service = service(client);

    service.init();
    service.init();
    awaitCalls(client, 1);
    service.close();

    assertEquals(1, client.calls.get());
  }

  @Test
  void closeCancelsInFlightRequestAndIgnoresLateSuccess() throws Exception {
    final CountDownLatch requestStarted = new CountDownLatch(1);
    final CountDownLatch releaseRequest = new CountDownLatch(1);
    final FakeClient client = new FakeClient(response(200, "etag-a", emptyConfig()));
    client.block(requestStarted, releaseRequest);
    final AgentlessConfigurationSource service = service(client);
    final ExecutorService runner = Executors.newSingleThreadExecutor();
    FeatureFlaggingGateway.addConfigListener(listener);

    try {
      final Future<Boolean> poll = runner.submit(service::pollOnce);
      assertTrue(requestStarted.await(1, TimeUnit.SECONDS));

      service.close();

      assertFalse(poll.get(1, TimeUnit.SECONDS));
      assertEquals(1, client.cancelCalls.get());
      assertEquals(1, client.calls.get());
      verifyNoInteractions(listener);
    } finally {
      releaseRequest.countDown();
      runner.shutdownNow();
    }
  }

  @Test
  void closeDuringIoFailurePreventsRetry() throws Exception {
    final CountDownLatch requestStarted = new CountDownLatch(1);
    final CountDownLatch releaseRequest = new CountDownLatch(1);
    final FakeClient client =
        new FakeClient(new SocketTimeoutException("slow HTTP configuration source"));
    client.block(requestStarted, releaseRequest);
    final AgentlessConfigurationSource service = service(client);
    final ExecutorService runner = Executors.newSingleThreadExecutor();

    try {
      final Future<Boolean> poll = runner.submit(service::pollOnce);
      assertTrue(requestStarted.await(1, TimeUnit.SECONDS));

      service.close();

      assertFalse(poll.get(1, TimeUnit.SECONDS));
      assertEquals(1, client.calls.get());
    } finally {
      releaseRequest.countDown();
      runner.shutdownNow();
    }
  }

  @Test
  void closeInterruptsRetryBackoff() throws Exception {
    final CountDownLatch backoffStarted = new CountDownLatch(1);
    final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    final FakeClient client =
        new FakeClient(
            new SocketTimeoutException("slow HTTP configuration source"),
            response(200, "etag-a", emptyConfig()));
    final AgentlessConfigurationSource service =
        new AgentlessConfigurationSource(
            HttpUrl.get("http://localhost" + CONFIG_PATH),
            config(),
            30_000,
            client,
            executor,
            delay -> {
              backoffStarted.countDown();
              TimeUnit.MINUTES.sleep(1);
            },
            () -> 1.0);

    service.init();
    assertTrue(backoffStarted.await(1, TimeUnit.SECONDS));

    service.close();

    assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
    assertEquals(1, client.calls.get());
  }

  @Test
  void closePreventsFurtherPolls() throws Exception {
    final FakeClient client = new FakeClient(response(200, "etag-a", emptyConfig()));
    final AgentlessConfigurationSource service = service(client);

    service.close();

    assertFalse(service.pollOnce());
    assertEquals(0, client.calls.get());
  }

  @Test
  void initAfterCloseDoesNotSchedulePoll() throws Exception {
    final FakeClient client = new FakeClient(response(200, "etag-a", emptyConfig()));
    final AgentlessConfigurationSource service = service(client);

    service.close();
    service.init();

    assertEquals(0, client.calls.get());
  }

  @Nested
  class SystemTestParity {
    @Test
    void preservesSystemTestSourceTransitionsAndLastKnownGoodState() throws Exception {
      final FakeClient client =
          new FakeClient(
              response(200, "etag-a", emptyConfig()),
              response(304, "etag-must-not-replace-a", null),
              response(509, null, null),
              response(200, "etag-b", emptyConfig()),
              response(200, "etag-c", "{not-json}"),
              response(401, null, null));
      final AgentlessConfigurationSource service = service(client);
      FeatureFlaggingGateway.addConfigListener(listener);

      assertTrue(service.pollOnce());
      assertTrue(service.pollOnce());
      assertTrue(service.pollOnce());
      assertFalse(service.pollOnce());
      assertFalse(service.pollOnce());

      verify(listener, times(2)).accept(any(ServerConfiguration.class));
      assertEquals("etag-a", client.requests.get(1).etag);
      assertEquals("etag-a", client.requests.get(2).etag);
      assertEquals("etag-a", client.requests.get(3).etag);
      assertEquals("etag-b", client.requests.get(4).etag);
      assertEquals("etag-b", client.requests.get(5).etag);
    }
  }

  private static AgentlessConfigurationSource service(final FakeClient client) {
    return service(client, delay -> {}, () -> 1.0);
  }

  private static AgentlessConfigurationSource service(
      final FakeClient client,
      final AgentlessConfigurationSource.RetrySleeper retrySleeper,
      final java.util.function.DoubleSupplier jitter) {
    final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    return new AgentlessConfigurationSource(
        HttpUrl.get("http://localhost" + CONFIG_PATH),
        config(),
        30_000,
        client,
        executor,
        retrySleeper,
        jitter);
  }

  private static Config config() {
    return config("datadoghq.com", "");
  }

  private static Config config(final String site, final String env) {
    final Config config = mock(Config.class);
    lenient()
        .when(config.getFeatureFlaggingConfigurationSourcePollIntervalSeconds())
        .thenReturn(30);
    lenient()
        .when(config.getFeatureFlaggingConfigurationSourceRequestTimeoutSeconds())
        .thenReturn(2);
    lenient().when(config.getApiKey()).thenReturn("test-api-key");
    lenient().when(config.getSite()).thenReturn(site);
    lenient().when(config.getEnv()).thenReturn(env);
    return config;
  }

  private static AgentlessConfigurationSource.UfcHttpResponse response(
      final int status, final String etag, final String body) {
    return new AgentlessConfigurationSource.UfcHttpResponse(
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

  private static void awaitCalls(final FakeClient client, final int count) throws Exception {
    for (int i = 0; i < 100; i++) {
      if (client.calls.get() >= count) {
        return;
      }
      TimeUnit.MILLISECONDS.sleep(10);
    }
    assertEquals(count, client.calls.get());
  }

  private static final class FakeClient implements AgentlessConfigurationSource.UfcHttpClient {
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger cancelCalls = new AtomicInteger();
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
    public AgentlessConfigurationSource.UfcHttpResponse fetch(
        final HttpUrl endpoint, final Config config, final String etag) throws IOException {
      calls.incrementAndGet();
      requests.add(new Request(config.getApiKey(), etag));
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
      if (response instanceof RuntimeException) {
        throw (RuntimeException) response;
      }
      return (AgentlessConfigurationSource.UfcHttpResponse) response;
    }

    @Override
    public void cancel() {
      cancelCalls.incrementAndGet();
      if (releaseRequest != null) {
        releaseRequest.countDown();
      }
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

    private Request(final String apiKey, final String etag) {
      this.apiKey = apiKey;
      this.etag = etag;
    }
  }
}
