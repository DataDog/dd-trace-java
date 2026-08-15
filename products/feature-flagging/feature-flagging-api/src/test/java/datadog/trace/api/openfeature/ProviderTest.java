package datadog.trace.api.openfeature;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import datadog.trace.api.featureflag.FeatureFlaggingRawBridge;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.exceptions.FatalError;
import dev.openfeature.sdk.exceptions.ProviderNotReadyError;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ProviderTest {

  private Provider first;
  private Provider second;

  @AfterEach
  void close() {
    if (first != null) {
      first.shutdown();
    }
    if (second != null) {
      second.shutdown();
    }
    FeatureFlaggingRawBridge.dispatchConfiguration(null);
    FeatureFlaggingRawBridge.setRuntimeConfiguration(null);
  }

  @Test
  void selectsCdnByDefaultAndRemoteConfigExplicitly() {
    assertEquals(
        RuntimeConfiguration.Source.CDN,
        RuntimeConfiguration.resolve(new Provider.Options()).source);
    assertEquals(
        RuntimeConfiguration.Source.REMOTE_CONFIG,
        RuntimeConfiguration.resolve(new Provider.Options().configurationSource("remote_config"))
            .source);
  }

  @Test
  void preservesStableAndLegacySourceSelection() {
    assertEquals(
        RuntimeConfiguration.Source.DISABLED,
        RuntimeConfiguration.resolve(new Provider.Options().enabled(false)).source);

    final Provider.Options legacyEnabled = new Provider.Options();
    legacyEnabled.legacyProviderEnabled = true;
    assertEquals(
        RuntimeConfiguration.Source.REMOTE_CONFIG,
        RuntimeConfiguration.resolve(legacyEnabled).source);

    final Provider.Options legacyDisabled = new Provider.Options();
    legacyDisabled.legacyProviderEnabled = false;
    assertEquals(
        RuntimeConfiguration.Source.DISABLED, RuntimeConfiguration.resolve(legacyDisabled).source);
    assertEquals(
        RuntimeConfiguration.Source.CDN,
        RuntimeConfiguration.resolve(legacyDisabled.configurationSource("agentless")).source);
  }

  @Test
  void usesAgentResolvedConfigurationBeforeProcessFallbacks() {
    final Map<String, Object> remoteConfiguration = new LinkedHashMap<>();
    remoteConfiguration.put("enabled", true);
    remoteConfiguration.put("source", "remote_config");
    FeatureFlaggingRawBridge.setRuntimeConfiguration(remoteConfiguration);

    assertEquals(
        RuntimeConfiguration.Source.REMOTE_CONFIG,
        RuntimeConfiguration.resolve(new Provider.Options()).source);

    final Map<String, Object> cdnConfiguration = new LinkedHashMap<>();
    cdnConfiguration.put("enabled", true);
    cdnConfiguration.put("source", "agentless");
    cdnConfiguration.put("cdn_base_url", "https://stable.example/config");
    cdnConfiguration.put("poll_interval_seconds", 41);
    cdnConfiguration.put("request_timeout_seconds", 7);
    cdnConfiguration.put("api_key", "stable-key");
    cdnConfiguration.put("site", "datadoghq.eu");
    cdnConfiguration.put("environment", "stable-env");
    FeatureFlaggingRawBridge.setRuntimeConfiguration(cdnConfiguration);

    final RuntimeConfiguration resolved = RuntimeConfiguration.resolve(new Provider.Options());

    assertEquals(RuntimeConfiguration.Source.CDN, resolved.source);
    assertEquals(URI.create("https://stable.example/config"), resolved.http.endpoint);
    assertEquals(Duration.ofSeconds(41), resolved.http.pollInterval);
    assertEquals(Duration.ofSeconds(7), resolved.http.requestTimeout);
    assertEquals("stable-key", resolved.http.apiKey);
    assertFalse(resolved.http.managedEndpoint);
    assertEquals(
        RuntimeConfiguration.Source.REMOTE_CONFIG,
        RuntimeConfiguration.resolve(new Provider.Options().configurationSource("remote_config"))
            .source);
  }

  @Test
  void disabledProviderDoesNotStartConfigurationSource() {
    first = new Provider(new Provider.Options().enabled(false).initTimeout(10, MILLISECONDS));

    final FatalError error =
        assertThrows(FatalError.class, () -> first.initialize(new MutableContext()));
    assertTrue(error.getMessage().contains("disabled by configuration"));
  }

  @Test
  void providerOwnsCdnLifecycleWithoutAgent() throws Exception {
    final AtomicInteger requests = new AtomicInteger();
    final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/config",
        exchange -> {
          requests.incrementAndGet();
          final byte[] response = UFC.getBytes(UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
    try {
      first =
          new Provider(
              new Provider.Options()
                  .cdnBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/config")
                  .pollInterval(Duration.ofMillis(50))
                  .requestTimeout(Duration.ofSeconds(1))
                  .initTimeout(1, java.util.concurrent.TimeUnit.SECONDS));
      assertEquals(0, requests.get());

      first.initialize(new MutableContext("subject"));

      assertTrue(requests.get() > 0);
      assertEquals(
          "hello",
          first
              .getStringEvaluation("message", "default", new MutableContext("subject"))
              .getValue());
      first.shutdown();
      first = null;
      final int stoppedAt = requests.get();
      Thread.sleep(150);
      assertEquals(stoppedAt, requests.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void providerInitializationDeadlineIncludesTheInitialRequest() throws Exception {
    final CountDownLatch requestStarted = new CountDownLatch(1);
    final CountDownLatch releaseRequest = new CountDownLatch(1);
    final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/config",
        exchange -> {
          requestStarted.countDown();
          try {
            releaseRequest.await(5, java.util.concurrent.TimeUnit.SECONDS);
            exchange.sendResponseHeaders(304, -1);
          } catch (final InterruptedException error) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    server.start();
    try {
      first =
          new Provider(
              new Provider.Options()
                  .cdnBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/config")
                  .requestTimeout(Duration.ofSeconds(5))
                  .initTimeout(100, MILLISECONDS));

      final long started = System.nanoTime();
      assertThrows(
          ProviderNotReadyError.class, () -> first.initialize(new MutableContext("subject")));
      final long elapsedMillis =
          java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

      assertTrue(requestStarted.await(1, java.util.concurrent.TimeUnit.SECONDS));
      assertTrue(elapsedMillis < 500, "Initialization took " + elapsedMillis + " milliseconds");
    } finally {
      releaseRequest.countDown();
      if (first != null) {
        first.shutdown();
        first = null;
      }
      server.stop(0);
    }
  }

  @Test
  void sharesOneReferenceCountedRuntimePerClassloader() throws Exception {
    FeatureFlaggingRawBridge.dispatchConfiguration(UFC.getBytes(UTF_8));
    final Provider.Options options =
        new Provider.Options().configurationSource("remote_config").initTimeout(100, MILLISECONDS);
    first = new Provider(options);
    second = new Provider(options);

    first.initialize(new MutableContext("first"));
    second.initialize(new MutableContext("second"));
    first.shutdown();
    first = null;
    FeatureFlaggingRawBridge.dispatchConfiguration(UFC.replace("hello", "updated").getBytes(UTF_8));

    assertEquals(
        "updated",
        second.getStringEvaluation("message", "default", new MutableContext("subject")).getValue());
  }

  @Test
  void rejectsDifferentOptionsWithinOneClassloader() throws Exception {
    FeatureFlaggingRawBridge.dispatchConfiguration(UFC.getBytes(UTF_8));
    first =
        new Provider(
            new Provider.Options()
                .configurationSource("remote_config")
                .initTimeout(100, MILLISECONDS));
    first.initialize(new MutableContext("subject"));
    second =
        new Provider(
            new Provider.Options()
                .cdnBaseUrl("http://127.0.0.1:1/config")
                .initTimeout(10, MILLISECONDS));

    final FatalError error =
        assertThrows(FatalError.class, () -> second.initialize(new MutableContext()));
    assertTrue(error.getMessage().contains("same configuration source and options"));
  }

  @Test
  void reportsInitializationTimeout() {
    final Evaluator evaluator = mock(Evaluator.class);
    first = new Provider(new Provider.Options().initTimeout(10, MILLISECONDS), evaluator);

    assertThrows(ProviderNotReadyError.class, () -> first.initialize(new MutableContext()));
  }

  @Test
  void mapsProviderCallsAndClosesInjectedEvaluator() throws Exception {
    final Evaluator evaluator = mock(Evaluator.class);
    when(evaluator.initialize(eq(10L), eq(MILLISECONDS), any())).thenReturn(true);
    when(evaluator.hasConfiguration()).thenReturn(true);
    when(evaluator.evaluate(eq(String.class), eq("key"), eq("default"), any()))
        .thenReturn(ProviderEvaluation.<String>builder().value("value").build());
    first = new Provider(new Provider.Options().initTimeout(10, MILLISECONDS), evaluator);

    first.initialize(new MutableContext());
    assertEquals(
        "value", first.getStringEvaluation("key", "default", new MutableContext()).getValue());
    first.shutdown();
    first = null;

    verify(evaluator).shutdown();
  }

  private static final String UFC =
      "{\"format\":\"SERVER\",\"environment\":{\"name\":\"test\"},\"flags\":{"
          + "\"message\":{\"key\":\"message\",\"enabled\":true,\"variationType\":\"STRING\","
          + "\"variations\":{\"on\":{\"key\":\"on\",\"value\":\"hello\"}},"
          + "\"allocations\":[{\"key\":\"allocation\",\"rules\":[],\"splits\":["
          + "{\"variationKey\":\"on\",\"shards\":[]}],\"doLog\":false}]}}}";
}
