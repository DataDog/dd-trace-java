package com.datadog.openfeature.internal.delivery;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.datadog.openfeature.internal.LocalHttpServer;
import com.datadog.openfeature.internal.config.TestSettings;
import com.datadog.openfeature.internal.delivery.FlagSelectionTestSupport.RecordingHealthMetrics;
import com.datadog.openfeature.internal.ufc.ServerConfiguration;
import com.datadog.openfeature.testlog.CapturingServiceProvider;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;
import org.tabletest.junit.TableTest;

class CdnConfigurationSourceFlagSelectionTest {
  private static final String CONFIG =
      "{\"data\":{\"type\":\"universal-flag-configuration\",\"attributes\":"
          + "{\"createdAt\":\"2024-04-17T19:40:53.716Z\",\"environment\":{\"name\":\"Test\"},"
          + "\"flags\":{}}}}";
  private static final String IGNORED = "DD-Flag-Selection-Ignored";
  private static final FlagSelectionRequest SELECTION =
      FlagSelectionRequest.of(asList("team:checkout", "a b"), singletonList("checkout.*"));
  private static final String SELECTION_QUERY =
      "selected_flag_tags=a%20b&selected_flag_tags=team%3Acheckout"
          + "&selected_flag_key_patterns=checkout.%2A";

  private LocalHttpServer server;
  private final RecordingHealthMetrics metrics = new RecordingHealthMetrics();
  private final List<ServerConfiguration> dispatched = new CopyOnWriteArrayList<>();

  @BeforeEach
  void setUp() throws IOException {
    this.server = new LocalHttpServer();
    FlagSelectionTestSupport.resetReported();
    CapturingServiceProvider.enable("com.datadog.openfeature", Level.WARN);
  }

  @AfterEach
  void tearDown() {
    this.server.close();
    CapturingServiceProvider.reset();
    FlagSelectionTestSupport.resetReported();
  }

  @Test
  void managedEndpointSendsTheSelectionAfterTheEnv() {
    final URI managed = CdnConfigurationSource.endpoint(TestSettings.of("env", "a b"));

    assertEquals(
        "https://ufc-server.ff-cdn.datadoghq.com/api/v2/feature-flagging/config/rules-based/server"
            + "?dd_env=a+b&"
            + SELECTION_QUERY,
        SELECTION.appendTo(managed).toString());
  }

  @TableTest({
    "scenario            | baseUrl                 | path                                                 | query         ",
    "custom path         | '/ufc.json?token=a%2Bb' | '/ufc.json'                                          | 'token=a%2Bb&'",
    "default path, query | '/?dd_env=test'         | '/api/v2/feature-flagging/config/rules-based/server' | 'dd_env=test&'",
    "default path        | '/'                     | '/api/v2/feature-flagging/config/rules-based/server' | ''            "
  })
  void mergesTheSelectionIntoTheCustomEndpointQuery(
      final String baseUrl, final String path, final String query) {
    this.server.enqueue(200, CONFIG);
    final CdnConfigurationSource source =
        new CdnConfigurationSource(
            TestSettings.of(
                "feature.flags.configuration.source.agentless.base.url",
                this.server.uri(baseUrl).toString()),
            this.metrics.services(),
            SELECTION);
    try {
      source.start(this.dispatched::add);
    } finally {
      source.close();
    }

    final LocalHttpServer.Request request = this.server.requests().get(0);
    assertEquals(path, request.uri.getRawPath());
    assertEquals(query + SELECTION_QUERY, request.uri.getRawQuery());
    assertEquals(SELECTION, source.selection());
  }

  @Test
  void reportsTheIgnoredSelectionOnlyAfterTheConfigurationIsApplied() {
    this.server.enqueue(200, CONFIG, IGNORED, "tag_load_failed");
    final List<List<String>> messagesWhenApplied = new CopyOnWriteArrayList<>();
    final CdnConfigurationSource source =
        source(SELECTION, configuration -> messagesWhenApplied.add(messages()));

    assertTrue(source.pollOnce());

    assertEquals(singletonList(emptyList()), messagesWhenApplied);
    assertEquals(
        singletonList(
            "ERROR flagSelection was ignored by the server (tag_load_failed). Loaded the full flag"
                + " configuration."),
        messages());
    assertEquals(singletonList("flag_selection.ignored|1|tag_load_failed"), this.metrics.counts);
  }

  @Test
  void reportsEachIgnoredReasonOncePerClassLoader() {
    this.server
        .enqueue(200, CONFIG, IGNORED, "too_many_values")
        .enqueue(200, CONFIG, IGNORED, "too_many_values")
        .enqueue(200, CONFIG, IGNORED, "invalid_tag");
    final CdnConfigurationSource source = source(SELECTION, this.dispatched::add);
    final CdnConfigurationSource other = source(SELECTION, this.dispatched::add);

    assertTrue(source.pollOnce());
    assertTrue(other.pollOnce());
    assertTrue(source.pollOnce());

    assertEquals(3, this.dispatched.size());
    assertEquals(
        asList(
            "ERROR flagSelection was ignored by the server (too_many_values). Loaded the full flag"
                + " configuration.",
            "ERROR flagSelection was ignored by the server (invalid_tag). Loaded the full flag"
                + " configuration."),
        messages());
    assertEquals(
        asList("flag_selection.ignored|1|too_many_values", "flag_selection.ignored|1|invalid_tag"),
        this.metrics.counts);
  }

  @TableTest({
    "scenario      | header                             ",
    "wrong case    | 'INVALID_TAG'                      ",
    "unknown       | 'server_busy'                      ",
    "33 characters | 'invalid_taginvalid_taginvalid_tag'"
  })
  void reportsAnUnexpectedIgnoredHeaderAsUnknown(final String header) {
    this.server.enqueue(200, CONFIG, IGNORED, header);

    assertTrue(source(SELECTION, this.dispatched::add).pollOnce());

    assertEquals(
        singletonList(
            "ERROR flagSelection was ignored by the server (unknown). Loaded the full flag"
                + " configuration."),
        messages());
    assertEquals(singletonList("flag_selection.ignored|1|unknown"), this.metrics.counts);
  }

  @Test
  void readsTheIgnoredHeaderOnlyOnAnAppliedConfiguration() {
    this.server
        .enqueue(304, null, IGNORED, "invalid_tag")
        .enqueue(404, null, IGNORED, "invalid_tag")
        .enqueue(500, null, IGNORED, "invalid_tag")
        .enqueue(500, null, IGNORED, "invalid_tag")
        .enqueue(500, null, IGNORED, "invalid_tag")
        .enqueue(200, "{not json", IGNORED, "invalid_tag")
        .enqueue(200, CONFIG, IGNORED, "");
    final CdnConfigurationSource source = source(SELECTION, this.dispatched::add);

    assertTrue(source.pollOnce());
    assertFalse(source.pollOnce());
    assertFalse(source.pollOnce());
    assertFalse(source.pollOnce());
    assertTrue(source.pollOnce());

    assertEquals(1, this.dispatched.size());
    assertTrue(messages().isEmpty(), messages().toString());
    assertTrue(this.metrics.counts.isEmpty());
  }

  @Test
  void ignoresTheHeaderWithoutASelection() {
    this.server.enqueue(200, CONFIG, IGNORED, "invalid_tag");
    final CdnConfigurationSource source = source(FlagSelectionRequest.NONE, this.dispatched::add);

    assertTrue(source.pollOnce());

    assertEquals("/ufc", this.server.requests().get(0).uri.toString());
    assertTrue(messages().isEmpty());
    assertTrue(this.metrics.counts.isEmpty());
  }

  @Test
  void keepsTheEtagOfTheSelectedConfiguration() {
    this.server.enqueue(200, CONFIG, "ETag", "\"selected\"").enqueue(304, null);
    final CdnConfigurationSource source = source(SELECTION, this.dispatched::add);

    assertTrue(source.pollOnce());
    assertTrue(source.pollOnce());

    final List<LocalHttpServer.Request> requests = this.server.requests();
    assertEquals(SELECTION_QUERY, requests.get(0).uri.getRawQuery());
    assertEquals(SELECTION_QUERY, requests.get(1).uri.getRawQuery());
    assertNull(requests.get(0).header("If-None-Match"));
    assertEquals("\"selected\"", requests.get(1).header("If-None-Match"));
    assertEquals(1, this.dispatched.size());
  }

  @Test
  void sendsNoSelectionWhenTheUrlWouldBeOverTheLimit() {
    // The endpoint fits in 8192 bytes, but not once the selection is added.
    final char[] padding = new char[8150];
    Arrays.fill(padding, 'x');
    this.server.otherwise(200, CONFIG, IGNORED, "invalid_tag");
    final CdnConfigurationSource source =
        source(this.server.uri("/ufc?pad=" + new String(padding)), SELECTION, this.dispatched::add);

    assertTrue(source.pollOnce());
    assertTrue(source.pollOnce());

    assertEquals(2, this.server.requests().size());
    for (final LocalHttpServer.Request request : this.server.requests()) {
      assertEquals("pad=" + new String(padding), request.uri.getRawQuery());
    }
    assertEquals(FlagSelectionRequest.urlTooLong(), source.selection());
    assertTrue(messages().isEmpty(), "the runtime reports the dropped selection");
    assertTrue(this.metrics.counts.isEmpty(), "the header of an unselected request is not read");
  }

  @Test
  void droppedSelectionIsNotSent() {
    final FlagSelectionRequest dropped = FlagSelectionRequest.of(singletonList(null), null);
    this.server.enqueue(200, CONFIG, IGNORED, "malformed_selection");
    final CdnConfigurationSource source = source(dropped, this.dispatched::add);

    assertTrue(source.pollOnce());

    assertNull(this.server.requests().get(0).uri.getRawQuery());
    assertEquals(dropped, source.selection());
    assertTrue(this.metrics.counts.isEmpty());
  }

  private CdnConfigurationSource source(
      final FlagSelectionRequest selection, final Consumer<ServerConfiguration> listener) {
    return source(this.server.uri("/ufc"), selection, listener);
  }

  private CdnConfigurationSource source(
      final URI endpoint,
      final FlagSelectionRequest selection,
      final Consumer<ServerConfiguration> listener) {
    final CdnConfigurationSource source =
        new CdnConfigurationSource(
            endpoint,
            selection,
            null,
            30_000,
            Duration.ofSeconds(5),
            HttpClient.newHttpClient(),
            mock(ScheduledExecutorService.class),
            delay -> {},
            () -> 1.0,
            this.metrics.services());
    source.setListenerForTest(listener);
    return source;
  }

  private static List<String> messages() {
    return CapturingServiceProvider.events().stream()
        .filter(e -> e.message.startsWith("flagSelection"))
        .map(e -> e.level + " " + e.message)
        .collect(Collectors.toList());
  }
}
