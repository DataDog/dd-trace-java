package com.datadog.openfeature.internal.delivery;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.datadog.openfeature.internal.LocalHttpServer;
import com.datadog.openfeature.internal.RuntimeServices;
import com.datadog.openfeature.internal.config.Settings;
import com.datadog.openfeature.internal.config.TestSettings;
import com.datadog.openfeature.internal.connector.HealthMetrics;
import com.datadog.openfeature.internal.ufc.ServerConfiguration;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class CdnConfigurationSourceTest {
  private static final long POLL_INTERVAL_MILLIS = 30_000;
  private static final String CONFIG =
      "{\"data\":{\"type\":\"universal-flag-configuration\",\"attributes\":"
          + "{\"createdAt\":\"2024-04-17T19:40:53.716Z\",\"environment\":{\"name\":\"Test\"},"
          + "\"flags\":{}}}}";
  private static final String RAW_UFC =
      "{\"createdAt\":\"2024-04-17T19:40:53.716Z\",\"environment\":{\"name\":\"Test\"},\"flags\":{}}";

  private LocalHttpServer server;
  private final List<ServerConfiguration> dispatched = new CopyOnWriteArrayList<>();
  private final List<Long> sleeps = new CopyOnWriteArrayList<>();

  @BeforeEach
  void setUp() throws IOException {
    this.server = new LocalHttpServer();
  }

  @AfterEach
  void tearDown() {
    this.server.close();
  }

  @Test
  void derivesDatadogUfcCdnEndpointFromSiteAndEnv() {
    final URI endpoint =
        CdnConfigurationSource.endpoint(TestSettings.of("site", "datadoghq.eu", "env", "prod"));

    assertEquals(
        "https://ufc-server.ff-cdn.datadoghq.eu/api/v2/feature-flagging/config/rules-based/server?dd_env=prod",
        endpoint.toString());
  }

  @Test
  void derivesDatadogUfcCdnEndpointWithoutEnv() {
    assertEquals(
        "https://ufc-server.ff-cdn.datadoghq.com/api/v2/feature-flagging/config/rules-based/server",
        CdnConfigurationSource.endpoint(TestSettings.of()).toString());
  }

  @Test
  void encodesEnvInDatadogUfcCdnEndpoint() {
    assertEquals(
        "https://ufc-server.ff-cdn.datadoghq.com/api/v2/feature-flagging/config/rules-based/server?dd_env=a+b%26c",
        CdnConfigurationSource.endpoint(TestSettings.of("env", "a b&c")).toString());
  }

  @TableTest({
    "scenario    | baseUrl                          | endpoint                                                                 ",
    "host only   | 'http://localhost:8080'          | 'http://localhost:8080/api/v2/feature-flagging/config/rules-based/server'",
    "root path   | 'http://localhost:8080/'         | 'http://localhost:8080/api/v2/feature-flagging/config/rules-based/server'",
    "custom path | 'https://proxy.example/ufc.json' | 'https://proxy.example/ufc.json'                                         ",
    "trimmed     | '  https://proxy.example/ufc  '  | 'https://proxy.example/ufc'                                              "
  })
  void resolvesConfiguredAgentlessBaseUrl(final String baseUrl, final String endpoint) {
    assertEquals(
        endpoint,
        CdnConfigurationSource.endpoint(
                TestSettings.of(
                    "feature.flags.configuration.source.agentless.base.url",
                    baseUrl,
                    "env",
                    "prod"))
            .toString());
  }

  @TableTest({
    "scenario      | baseUrl       ",
    "no scheme     | 'localhost'   ",
    "relative path | 'relative/ufc'",
    "invalid       | 'http://[::1' "
  })
  void rejectsInvalidConfiguredAgentlessBaseUrl(final String baseUrl) {
    final Settings settings =
        TestSettings.of("feature.flags.configuration.source.agentless.base.url", baseUrl);

    assertThrows(IllegalArgumentException.class, () -> CdnConfigurationSource.endpoint(settings));
  }

  @Test
  void customEndpointDoesNotSendApiKey() throws Exception {
    this.server.enqueue(200, CONFIG);
    final CdnConfigurationSource source =
        new CdnConfigurationSource(
            TestSettings.of(
                "feature.flags.configuration.source.agentless.base.url",
                this.server.uri("/").toString(),
                "api-key",
                "secret"),
            new RuntimeServices(HealthMetrics.NOOP));
    try {
      source.start(this.dispatched::add);
    } finally {
      source.close();
    }

    assertEquals(1, this.dispatched.size());
    assertNull(this.server.requests().get(0).header("DD-API-KEY"));
  }

  @Test
  void sendsApiKeyAndMetadataHeaders() throws Exception {
    this.server.enqueue(200, CONFIG);

    assertTrue(source("secret").pollOnce());

    final LocalHttpServer.Request request = this.server.requests().get(0);
    assertEquals("GET", request.method);
    assertEquals("secret", request.header("DD-API-KEY"));
    assertEquals("gzip", request.header("Accept-Encoding"));
    assertEquals("java", request.header("Datadog-Meta-Lang"));
    assertNull(request.header("If-None-Match"));
  }

  @Test
  void appliesJsonApiConfiguration() {
    this.server.enqueue(200, CONFIG, "ETag", "\"v1\"");

    assertTrue(source().pollOnce());

    assertEquals(1, this.dispatched.size());
    assertEquals("Test", this.dispatched.get(0).environment.name);
  }

  @Test
  void decodesGzipResponses() {
    this.server.enqueueGzip(200, CONFIG);

    assertTrue(source().pollOnce());

    assertEquals(1, this.dispatched.size());
  }

  @Test
  void keepsLastKnownGoodOnRawUfcAndMalformedPayloads() {
    this.server.enqueue(200, CONFIG).enqueue(200, RAW_UFC).enqueue(200, "{not json");
    final CdnConfigurationSource source = source();

    assertTrue(source.pollOnce());
    assertFalse(source.pollOnce());
    assertFalse(source.pollOnce());

    assertEquals(1, this.dispatched.size());
  }

  @Test
  void usesEtagAndSkipsDispatchOnUnchangedConfig() {
    this.server.enqueue(200, CONFIG, "ETag", "\"v1\"").enqueue(304, null);
    final CdnConfigurationSource source = source();

    assertTrue(source.pollOnce());
    assertTrue(source.pollOnce());

    assertEquals(1, this.dispatched.size());
    assertEquals("\"v1\"", this.server.requests().get(1).header("If-None-Match"));
  }

  @Test
  void ignoresBlankEtagAndClearsPreviousEtag() {
    this.server
        .enqueue(200, CONFIG, "ETag", "\"v1\"")
        .enqueue(200, CONFIG)
        .enqueue(200, CONFIG, "ETag", " ")
        .enqueue(200, CONFIG);
    final CdnConfigurationSource source = source();

    for (int i = 0; i < 4; i++) {
      assertTrue(source.pollOnce());
    }

    assertEquals("\"v1\"", this.server.requests().get(1).header("If-None-Match"));
    assertNull(this.server.requests().get(2).header("If-None-Match"));
    assertNull(this.server.requests().get(3).header("If-None-Match"));
  }

  @Test
  void coldNotModifiedDoesNotEstablishEtag() {
    this.server.enqueue(304, null, "ETag", "\"v1\"").enqueue(200, CONFIG);
    final CdnConfigurationSource source = source();

    assertTrue(source.pollOnce());
    assertTrue(source.pollOnce());

    assertNull(this.server.requests().get(1).header("If-None-Match"));
    assertEquals(1, this.dispatched.size());
  }

  @Test
  void failedDispatchDoesNotAdvanceEtag() {
    this.server.enqueue(200, CONFIG, "ETag", "\"v1\"").enqueue(200, CONFIG, "ETag", "\"v2\"");
    final CdnConfigurationSource source = source();
    final RuntimeException failure = new IllegalStateException("listener failure");
    source.setListenerForTest(
        configuration -> {
          throw failure;
        });

    assertThrows(IllegalStateException.class, source::pollOnce);
    source.setListenerForTest(this.dispatched::add);
    assertTrue(source.pollOnce());

    assertNull(this.server.requests().get(1).header("If-None-Match"));
  }

  @TableTest({
    "scenario     | status",
    "unauthorized | 401   ",
    "forbidden    | 403   ",
    "not found    | 404   ",
    "no content   | 204   "
  })
  void keepsLastKnownGoodOnNonOkResponses(final int status) {
    this.server.enqueue(200, CONFIG).enqueue(status, null);
    final CdnConfigurationSource source = source();

    assertTrue(source.pollOnce());
    assertFalse(source.pollOnce());

    assertEquals(1, this.dispatched.size());
    assertEquals(2, this.server.requests().size());
  }

  @TableTest({
    "scenario          | status",
    "client timeout    | 408   ",
    "too many requests | 429   ",
    "server error      | 503   "
  })
  void retriesRetryableStatusBeforeApplyingConfig(final int status) {
    this.server.enqueue(status, null).enqueue(status, null).enqueue(200, CONFIG);

    assertTrue(source().pollOnce());

    assertEquals(3, this.server.requests().size());
    assertEquals(1, this.dispatched.size());
    assertEquals(2, this.sleeps.size());
  }

  @Test
  void stopsAfterThreeAttemptsOnRetryableStatus() {
    this.server.otherwise(500, null);

    assertFalse(source().pollOnce());

    assertEquals(CdnConfigurationSource.MAX_ATTEMPTS, this.server.requests().size());
    assertTrue(this.dispatched.isEmpty());
  }

  @Test
  void retriesServerErrorThenKeepsColdStateOnNotModified() {
    this.server.enqueue(500, null).enqueue(304, null);

    assertTrue(source().pollOnce());

    assertEquals(2, this.server.requests().size());
    assertTrue(this.dispatched.isEmpty());
  }

  @Test
  void retriesIoFailuresUntilAttemptsAreExhausted() throws Exception {
    final URI unreachable;
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      unreachable = URI.create("http://127.0.0.1:" + socket.getLocalPort() + "/ufc");
    }
    final CdnConfigurationSource source =
        source(unreachable, null, mock(ScheduledExecutorService.class));

    assertFalse(source.pollOnce());

    assertEquals(CdnConfigurationSource.MAX_ATTEMPTS - 1, this.sleeps.size());
  }

  @Test
  void usesIntervalAwareRetryBackoff() {
    this.server.enqueue(503, null).enqueue(503, null).enqueue(200, CONFIG);

    assertTrue(source().pollOnce());

    assertEquals(
        List.of(
            CdnConfigurationSource.retryDelayMillis(POLL_INTERVAL_MILLIS, 1, 1.0),
            CdnConfigurationSource.retryDelayMillis(POLL_INTERVAL_MILLIS, 2, 1.0)),
        this.sleeps);
  }

  @TableTest({
    "scenario            | pollIntervalMillis | attempt | jitter | delay",
    "first clamped low   | 1000               | 1       | 1.0    | 2000 ",
    "first proportional  | 30000              | 1       | 1.0    | 5000 ",
    "first clamped high  | 600000             | 1       | 1.0    | 10000",
    "second clamped low  | 1000               | 2       | 1.0    | 5000 ",
    "second proportional | 30000              | 2       | 1.0    | 10000",
    "second clamped high | 600000             | 2       | 1.0    | 30000",
    "jitter down         | 30000              | 1       | 0.8    | 4000 ",
    "jitter up           | 30000              | 2       | 1.2    | 12000"
  })
  void clampsAndJittersRetryBackoff(
      final long pollIntervalMillis, final int attempt, final double jitter, final long delay) {
    assertEquals(
        delay, CdnConfigurationSource.retryDelayMillis(pollIntervalMillis, attempt, jitter));
  }

  @Test
  void rejectsUnsupportedRetryAttempt() {
    assertThrows(
        IllegalArgumentException.class,
        () -> CdnConfigurationSource.retryDelayMillis(POLL_INTERVAL_MILLIS, 3, 1.0));
  }

  @Test
  void startCompletesFirstPollAndCloseCancelsScheduledPoll() {
    this.server.enqueue(200, CONFIG);
    final ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
    final ScheduledFuture<?> future = mock(ScheduledFuture.class);
    doReturn(future)
        .when(executor)
        .scheduleWithFixedDelay(
            any(Runnable.class),
            eq(POLL_INTERVAL_MILLIS),
            eq(POLL_INTERVAL_MILLIS),
            eq(MILLISECONDS));
    final CdnConfigurationSource source = source(this.server.uri("/ufc"), null, executor);

    source.start(this.dispatched::add);
    source.start(this.dispatched::add);

    assertEquals(1, this.dispatched.size());
    assertEquals(1, this.server.requests().size());
    verify(executor, times(1))
        .scheduleWithFixedDelay(
            any(Runnable.class),
            eq(POLL_INTERVAL_MILLIS),
            eq(POLL_INTERVAL_MILLIS),
            eq(MILLISECONDS));

    source.close();
    verify(future).cancel(true);
    verify(executor).shutdownNow();
    assertFalse(source.pollOnce());
  }

  @Test
  void startSurvivesListenerFailure() {
    this.server.enqueue(200, CONFIG);
    final ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
    final CdnConfigurationSource source = source(this.server.uri("/ufc"), null, executor);

    source.start(
        configuration -> {
          throw new IllegalStateException("listener failure");
        });

    verify(executor)
        .scheduleWithFixedDelay(any(Runnable.class), any(Long.class), any(Long.class), any());
  }

  @Test
  void startAfterCloseDoesNotPoll() {
    final ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
    final CdnConfigurationSource source = source(this.server.uri("/ufc"), null, executor);

    source.close();
    source.start(this.dispatched::add);

    assertTrue(this.server.requests().isEmpty());
    verify(executor, never())
        .scheduleWithFixedDelay(any(Runnable.class), any(Long.class), any(Long.class), any());
  }

  @Test
  void closeInterruptsRetryBackoff() throws Exception {
    this.server.otherwise(503, null);
    final CdnConfigurationSource source =
        new CdnConfigurationSource(
            this.server.uri("/ufc"),
            null,
            TimeUnit.MINUTES.toMillis(10),
            Duration.ofSeconds(5),
            HttpClient.newHttpClient(),
            mock(ScheduledExecutorService.class),
            MILLISECONDS::sleep,
            () -> 1.0);
    final boolean[] result = {true};
    final Thread poller = new Thread(() -> result[0] = source.pollOnce());
    poller.start();
    this.server.received().poll(5, SECONDS);

    source.close();
    poller.join(SECONDS.toMillis(5));

    assertFalse(poller.isAlive(), "close must interrupt the retry backoff");
    assertFalse(result[0]);
    assertEquals(1, this.server.requests().size());
  }

  private CdnConfigurationSource source() {
    return source(null);
  }

  private CdnConfigurationSource source(final String apiKey) {
    final CdnConfigurationSource source =
        source(this.server.uri("/ufc"), apiKey, mock(ScheduledExecutorService.class));
    source.setListenerForTest(this.dispatched::add);
    return source;
  }

  private CdnConfigurationSource source(
      final URI endpoint, final String apiKey, final ScheduledExecutorService executor) {
    final CdnConfigurationSource source =
        new CdnConfigurationSource(
            endpoint,
            apiKey,
            POLL_INTERVAL_MILLIS,
            Duration.ofSeconds(5),
            HttpClient.newHttpClient(),
            executor,
            this.sleeps::add,
            () -> 1.0);
    source.setListenerForTest(this.dispatched::add);
    return source;
  }
}
