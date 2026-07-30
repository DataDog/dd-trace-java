package datadog.trace.api.openfeature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class StandaloneRuntimeConfigurationTest {

  private static final String ENABLED = "dd.feature.flags.enabled";
  private static final String SOURCE = "dd.feature.flags.configuration.source";
  private static final String LEGACY_ENABLED = "dd.experimental.flagging.provider.enabled";
  private static final String BASE_URL = "dd.feature.flags.configuration.source.agentless.base.url";
  private static final String POLL_INTERVAL =
      "dd.feature.flags.configuration.source.agentless.poll.interval.seconds";
  private static final String REQUEST_TIMEOUT =
      "dd.feature.flags.configuration.source.agentless.request.timeout.seconds";
  private static final String API_KEY = "dd.api.key";

  @AfterEach
  void clearProperties() {
    System.clearProperty(ENABLED);
    System.clearProperty(SOURCE);
    System.clearProperty(LEGACY_ENABLED);
    System.clearProperty(BASE_URL);
    System.clearProperty(POLL_INTERVAL);
    System.clearProperty(REQUEST_TIMEOUT);
    System.clearProperty(API_KEY);
  }

  @Test
  void defaultsToAgentless() {
    assertEquals(
        StandaloneRuntimeConfiguration.Source.CDN,
        StandaloneRuntimeConfiguration.resolveSource(null, null, null));
  }

  @Test
  void explicitAgentlessOverridesLegacyRemoteConfiguration() {
    System.setProperty(SOURCE, "agentless");
    System.setProperty(LEGACY_ENABLED, "true");

    final StandaloneRuntimeConfiguration configuration = StandaloneRuntimeConfiguration.resolve();

    assertEquals(StandaloneRuntimeConfiguration.Source.CDN, configuration.source);
  }

  @Test
  void preservesLegacyRemoteConfigurationDefault() {
    System.setProperty(LEGACY_ENABLED, "true");

    final StandaloneRuntimeConfiguration configuration = StandaloneRuntimeConfiguration.resolve();

    assertEquals(StandaloneRuntimeConfiguration.Source.REMOTE_CONFIG, configuration.source);
  }

  @Test
  void stableKillSwitchDisablesExplicitSource() {
    System.setProperty(ENABLED, "false");
    System.setProperty(SOURCE, "agentless");

    final StandaloneRuntimeConfiguration configuration = StandaloneRuntimeConfiguration.resolve();

    assertEquals(StandaloneRuntimeConfiguration.Source.DISABLED, configuration.source);
  }

  @Test
  void rejectsUnsupportedSource() {
    System.setProperty(SOURCE, "offline");

    assertThrows(IllegalArgumentException.class, StandaloneRuntimeConfiguration::resolve);
  }

  @Test
  void resolvesCustomEndpointAndPollingOptions() {
    System.setProperty(SOURCE, "agentless");
    System.setProperty(BASE_URL, "http://127.0.0.1:8080/config?tenant=test");
    System.setProperty(POLL_INTERVAL, "7");
    System.setProperty(REQUEST_TIMEOUT, "2");

    final StandaloneRuntimeConfiguration configuration = StandaloneRuntimeConfiguration.resolve();

    assertEquals(
        "http://127.0.0.1:8080/config?tenant=test", configuration.http.endpoint.toString());
    assertEquals(Duration.ofSeconds(7), configuration.http.pollInterval);
    assertEquals(Duration.ofSeconds(2), configuration.http.requestTimeout);
  }

  @Test
  void treatsBlankBaseUrlsAsTheManagedEndpoint() {
    System.setProperty(API_KEY, "secret");
    for (final String baseUrl : new String[] {"", "   "}) {
      System.setProperty(BASE_URL, baseUrl);

      final StandaloneRuntimeConfiguration configuration = StandaloneRuntimeConfiguration.resolve();

      assertEquals(
          "https://ufc-server.ff-cdn.datadoghq.com/api/v2/feature-flagging/config/rules-based/server",
          configuration.http.endpoint.toString());
      assertTrue(configuration.http.managedEndpoint);
      assertEquals("secret", configuration.http.apiKey);
    }
  }

  @Test
  void usesDefaultsForInvalidPollingOptions() {
    System.setProperty(POLL_INTERVAL, "invalid");
    System.setProperty(REQUEST_TIMEOUT, "0");

    StandaloneRuntimeConfiguration configuration = StandaloneRuntimeConfiguration.resolve();

    assertEquals(Duration.ofSeconds(30), configuration.http.pollInterval);
    assertEquals(Duration.ofSeconds(5), configuration.http.requestTimeout);

    System.setProperty(POLL_INTERVAL, "-1");
    System.setProperty(REQUEST_TIMEOUT, "invalid");

    configuration = StandaloneRuntimeConfiguration.resolve();

    assertEquals(Duration.ofSeconds(30), configuration.http.pollInterval);
    assertEquals(Duration.ofSeconds(5), configuration.http.requestTimeout);
  }

  @Test
  void comparesResolvedConfigurationsByEffectiveOptions() {
    System.setProperty(BASE_URL, "https://example.test/config");
    System.setProperty(API_KEY, "key");
    final StandaloneRuntimeConfiguration first = StandaloneRuntimeConfiguration.resolve();
    final StandaloneRuntimeConfiguration equivalent = StandaloneRuntimeConfiguration.resolve();

    assertEquals(first, first);
    assertEquals(first, equivalent);
    assertEquals(first.hashCode(), equivalent.hashCode());
    assertNotEquals(first, null);
    assertNotEquals(first, "configuration");

    System.setProperty(API_KEY, "other");
    assertNotEquals(first, StandaloneRuntimeConfiguration.resolve());

    System.clearProperty(API_KEY);
    System.clearProperty(BASE_URL);
    System.setProperty(ENABLED, "false");
    final StandaloneRuntimeConfiguration disabled = StandaloneRuntimeConfiguration.resolve();
    assertEquals(disabled, StandaloneRuntimeConfiguration.resolve());
    assertEquals(disabled.hashCode(), StandaloneRuntimeConfiguration.resolve().hashCode());

    System.clearProperty(ENABLED);
    System.setProperty(SOURCE, "remote_config");
    assertNotEquals(disabled, StandaloneRuntimeConfiguration.resolve());
  }
}
