package datadog.trace.api.openfeature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

  @AfterEach
  void clearProperties() {
    System.clearProperty(ENABLED);
    System.clearProperty(SOURCE);
    System.clearProperty(LEGACY_ENABLED);
    System.clearProperty(BASE_URL);
    System.clearProperty(POLL_INTERVAL);
    System.clearProperty(REQUEST_TIMEOUT);
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
}
