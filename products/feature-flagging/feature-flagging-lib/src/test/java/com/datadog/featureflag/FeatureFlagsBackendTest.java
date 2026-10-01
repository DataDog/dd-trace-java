package com.datadog.featureflag;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.remoteconfig.Product;
import datadog.trace.api.Config;
import datadog.trace.api.telemetry.CoreMetricCollector;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class FeatureFlagsBackendTest {
  private final SharedCommunicationObjects sco = mock(SharedCommunicationObjects.class);
  private final Config config = mock(Config.class);
  private final EventProxy eventProxy = mock(EventProxy.class);
  private final AgentSpan root = rootSpan();
  // SpanEnrichmentWriter is final, so the test drives a real one bound to a mocked root span.
  private final SpanEnrichmentWriter spanEnrichmentWriter =
      new SpanEnrichmentWriter(() -> this.root);
  private final CoreMetricCollector metrics = mock(CoreMetricCollector.class);
  private final FeatureFlagsBackend backend =
      new FeatureFlagsBackend(
          this.sco, this.config, this.eventProxy, this.spanEnrichmentWriter, this.metrics);

  private static AgentSpan rootSpan() {
    final AgentSpan root = mock(AgentSpan.class);
    when(root.getLocalRootSpan()).thenReturn(root);
    return root;
  }

  @Test
  void resolvesSettingsWithAgentDefaultsFromTheAgentConfiguration() {
    when(this.config.getApiKey()).thenReturn("key");
    when(this.config.getSite()).thenReturn("datadoghq.eu");
    when(this.config.getEnv()).thenReturn("prod");
    when(this.config.getServiceName()).thenReturn("service");
    when(this.config.getVersion()).thenReturn("1.0");
    final Properties properties = new Properties();
    properties.setProperty("feature.flags.configuration.source", "agentless");
    when(this.config.configProvider())
        .thenReturn(ConfigProvider.withPropertiesOverride(properties));

    assertEquals("key", this.backend.setting("api-key"));
    assertEquals("datadoghq.eu", this.backend.setting("site"));
    assertEquals("prod", this.backend.setting("env"));
    assertEquals("service", this.backend.setting("service"));
    assertEquals("1.0", this.backend.setting("version"));
    assertEquals("agentless", this.backend.setting("feature.flags.configuration.source"));
  }

  @Test
  void sharesOneRemoteConfigServiceAcrossSubscriptions() {
    final ConfigurationPoller poller = mock(ConfigurationPoller.class);
    when(this.sco.configurationPoller(this.config)).thenReturn(poller);

    this.backend.subscribeRemoteConfig(content -> {});
    this.backend.subscribeRemoteConfig(content -> {});

    verify(this.sco, times(1)).configurationPoller(this.config);
    verify(poller, times(1)).addCapabilities(anyLong());
  }

  @Test
  void failsToSubscribeWithoutRemoteConfig() {
    assertThrows(
        IllegalStateException.class, () -> this.backend.subscribeRemoteConfig(content -> {}));
  }

  @Test
  void closeUnsubscribesRemoteConfigAndClearsSpanEnrichment() {
    final ConfigurationPoller poller = mock(ConfigurationPoller.class);
    when(this.sco.configurationPoller(this.config)).thenReturn(poller);
    this.backend.subscribeRemoteConfig(content -> {});
    this.backend.enrichSerialId(1, false, null);

    this.backend.close();

    verify(poller).removeListeners(Product.FFE_FLAGS);
    assertTrue(this.spanEnrichmentWriter.states().isEmpty());
  }

  @Test
  void delegatesEventsMetricsAndSpanEnrichment() throws Exception {
    final byte[] payload = "{}".getBytes(UTF_8);
    when(this.eventProxy.isAvailable()).thenReturn(true);
    when(this.eventProxy.post("exposures", payload)).thenReturn(true);

    assertTrue(this.backend.isEventProxyAvailable());
    assertTrue(this.backend.postEvent("exposures", payload));
    this.backend.count("flagevaluation.rows.dropped", 3, "closed");
    this.backend.count("flagevaluation.payload.splits", 1, null);
    this.backend.enrichSerialId(42, true, "user");
    this.backend.enrichRuntimeDefault("flag", "default");

    verify(this.metrics).count("flagevaluation.rows.dropped", 3, "reason:closed");
    verify(this.metrics).count("flagevaluation.payload.splits", 1, null);
    final SpanEnrichmentAccumulator state = this.spanEnrichmentWriter.states().peek(this.root);
    assertNotNull(state);
    assertTrue(state.hasData());
  }
}
