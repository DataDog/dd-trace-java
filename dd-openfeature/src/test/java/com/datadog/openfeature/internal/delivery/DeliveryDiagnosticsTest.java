package com.datadog.openfeature.internal.delivery;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.datadog.openfeature.internal.LocalHttpServer;
import com.datadog.openfeature.internal.bridge.v1.ConfigurationSource;
import com.datadog.openfeature.internal.ufc.ServerConfiguration;
import com.datadog.openfeature.testlog.CapturingServiceProvider;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

class DeliveryDiagnosticsTest {
  private static final String CONFIG =
      "{\"data\":{\"type\":\"universal-flag-configuration\",\"attributes\":"
          + "{\"createdAt\":\"2024-04-17T19:40:53.716Z\",\"environment\":{\"name\":\"Test\"},"
          + "\"flags\":{}}}}";

  private LocalHttpServer server;

  @BeforeEach
  void setUp() throws IOException {
    this.server = new LocalHttpServer();
    CapturingServiceProvider.reset();
    CapturingServiceProvider.enable("com.datadog.openfeature", Level.DEBUG);
  }

  @AfterEach
  void tearDown() {
    CapturingServiceProvider.reset();
    this.server.close();
  }

  @Test
  void agentlessReportsAppliedAndUnchangedConfigurations() {
    this.server.enqueue(200, CONFIG, "ETag", "\"v1\"").enqueue(304, null);
    final CdnConfigurationSource source = source();

    assertTrue(source.pollOnce());
    assertTrue(source.pollOnce());

    assertLogged("agentless configuration applied: etag=\"v1\"");
    assertLogged("agentless configuration unchanged (HTTP 304)");
    // A successful poll reports its outcome once: a retry line is only for a retry.
    assertTrue(messages("poll attempt").isEmpty(), messages("").toString());
  }

  @Test
  void agentlessReportsAFailedAttemptWithoutItsStackTrace() {
    final CdnConfigurationSource source = source(URI.create("http://127.0.0.1:1/ufc"), null);

    assertFalse(source.pollOnce());

    final List<CapturingServiceProvider.Event> attempts =
        CapturingServiceProvider.events().stream()
            .filter(e -> e.message.contains("poll attempt"))
            .collect(Collectors.toList());
    assertEquals(2, attempts.size(), attempts.toString());
    for (final CapturingServiceProvider.Event attempt : attempts) {
      assertNull(attempt.throwable, attempt.toString());
    }
  }

  @Test
  void agentlessReportsAMalformedPayloadAsRetainingTheConfiguration() {
    this.server.enqueue(200, "{not json");

    assertFalse(source().pollOnce());

    assertLogged("malformed payload");
    assertLogged("retaining the current configuration");
  }

  @Test
  void agentlessReportsRetriesAndTheirDelay() {
    this.server.enqueue(503, null).enqueue(200, CONFIG);

    assertTrue(source().pollOnce());

    assertLogged("poll attempt 1/3 returned HTTP 503; retrying in");
  }

  @Test
  void agentlessReportsItsLifecycleWithoutQueryOrCredentials() {
    final CdnConfigurationSource source = source("secret");
    this.server.enqueue(200, CONFIG);

    source.start(configuration -> {});
    source.close();

    final String starting = logged("starting agentless configuration source");
    assertTrue(starting.contains("pollIntervalMillis=30000"), starting);
    assertFalse(starting.contains("/ufc"), starting);
    assertFalse(starting.contains("secret"), starting);
    assertFalse(starting.contains("token"), starting);
    assertFalse(starting.contains("hidden"), starting);
    assertLogged("agentless configuration source stopping");
  }

  @Test
  void agentlessReportsTheOriginOfTheEndpointOnly() {
    final URI server = this.server.uri("/ufc?token=hidden");
    final URI endpoint =
        URI.create(
            "http://user:s3cret@"
                + server.getHost()
                + ":"
                + server.getPort()
                + "/path-secret/ufc?token=hidden");
    this.server.enqueue(200, CONFIG);
    final CdnConfigurationSource source = source(endpoint, null);

    source.start(configuration -> {});
    source.close();

    final String starting = logged("starting agentless configuration source");
    assertTrue(starting.contains("http://" + server.getHost() + ":" + server.getPort()), starting);
    assertFalse(starting.contains("user"), starting);
    assertFalse(starting.contains("s3cret"), starting);
    assertFalse(starting.contains("path-secret"), starting);
    assertFalse(starting.contains("hidden"), starting);
  }

  @Test
  void remoteConfigurationReportsItsSubscriptionAndMalformedDocuments() {
    final CapturingSource connector = new CapturingSource();
    final RemoteConfigurationService service = new RemoteConfigurationService(connector);
    service.start(configuration -> {});

    connector.listener.accept("{\"environment\":{\"name\":\"Test\"},\"flags\":{}}".getBytes(UTF_8));
    connector.listener.accept("{not json".getBytes(UTF_8));
    connector.listener.accept(null);

    assertLogged("subscribing to Remote Configuration");
    assertLogged("malformed flag configuration");
  }

  private CdnConfigurationSource source() {
    return source(null);
  }

  private CdnConfigurationSource source(final String apiKey) {
    return source(this.server.uri("/ufc?token=hidden"), apiKey);
  }

  private CdnConfigurationSource source(final URI endpoint, final String apiKey) {
    final CdnConfigurationSource source =
        new CdnConfigurationSource(
            endpoint,
            apiKey,
            30_000,
            Duration.ofSeconds(5),
            HttpClient.newHttpClient(),
            mock(ScheduledExecutorService.class),
            delay -> {},
            () -> 1.0);
    source.setListenerForTest(new CopyOnWriteArrayList<ServerConfiguration>()::add);
    return source;
  }

  private void assertLogged(final String text) {
    assertFalse(messages(text).isEmpty(), "Missing log: " + text + " in " + messages(""));
  }

  private String logged(final String text) {
    final List<String> found = messages(text);
    assertEquals(1, found.size(), found.toString());
    return found.get(0);
  }

  private static List<String> messages(final String text) {
    return CapturingServiceProvider.events().stream()
        .map(e -> e.message)
        .filter(message -> message.contains(text))
        .collect(Collectors.toList());
  }

  private static final class CapturingSource implements ConfigurationSource {
    volatile Consumer<byte[]> listener;

    @Override
    public AutoCloseable subscribe(final Consumer<byte[]> listener) {
      this.listener = listener;
      return () -> {};
    }
  }
}
