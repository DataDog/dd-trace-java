package com.datadog.featureflag;

import static datadog.trace.api.config.RemoteConfigConfig.REMOTE_CONFIGURATION_ENABLED;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.remoteconfig.Capabilities;
import datadog.remoteconfig.ConfigurationDeserializer;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.remoteconfig.Product;
import datadog.trace.api.Config;
import datadog.trace.api.config.FeatureFlaggingConfig;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.junit.utils.config.WithConfig;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

class FeatureFlaggingSystemTest {

  @Test
  @WithConfig(key = FeatureFlaggingConfig.FLAGGING_EVALUATION_COUNTS_ENABLED, value = "true")
  void testFeatureFlagSystemInitialization() {
    ConfigurationPoller poller = mock(ConfigurationPoller.class);
    DDAgentFeaturesDiscovery discovery = mock(DDAgentFeaturesDiscovery.class);
    SharedCommunicationObjects sharedCommunicationObjects = mock(SharedCommunicationObjects.class);
    when(discovery.supportsEvpProxy()).thenReturn(true);
    when(discovery.getEvpProxyEndpoint()).thenReturn("/evp_proxy/");
    when(sharedCommunicationObjects.configurationPoller(any(Config.class))).thenReturn(poller);
    when(sharedCommunicationObjects.featuresDiscovery(any(Config.class))).thenReturn(discovery);
    sharedCommunicationObjects.agentUrl = HttpUrl.get("http://localhost");
    sharedCommunicationObjects.agentHttpClient = new OkHttpClient.Builder().build();

    FeatureFlaggingSystem.start(sharedCommunicationObjects);

    verify(poller).addCapabilities(Capabilities.CAPABILITY_FFE_FLAG_CONFIGURATION_RULES);
    verify(poller).addListener(eq(Product.FFE_FLAGS), any(ConfigurationDeserializer.class), any());
    verify(poller).start();
    assertNotNull(FeatureFlaggingGateway.getFlagEvalWriter());

    FeatureFlaggingSystem.stop();
    assertNull(FeatureFlaggingGateway.getFlagEvalWriter());

    verify(poller).removeCapabilities(Capabilities.CAPABILITY_FFE_FLAG_CONFIGURATION_RULES);
    verify(poller).removeListeners(Product.FFE_FLAGS);
    verify(poller).stop();
  }

  @Test
  @WithConfig(key = FeatureFlaggingConfig.FLAGGING_EVALUATION_COUNTS_ENABLED, value = "false")
  void testFlagEvaluationWriterCanBeDisabled() {
    ConfigurationPoller poller = mock(ConfigurationPoller.class);
    DDAgentFeaturesDiscovery discovery = mock(DDAgentFeaturesDiscovery.class);
    SharedCommunicationObjects sharedCommunicationObjects = mock(SharedCommunicationObjects.class);
    when(discovery.supportsEvpProxy()).thenReturn(true);
    when(discovery.getEvpProxyEndpoint()).thenReturn("/evp_proxy/");
    when(sharedCommunicationObjects.configurationPoller(any(Config.class))).thenReturn(poller);
    when(sharedCommunicationObjects.featuresDiscovery(any(Config.class))).thenReturn(discovery);
    sharedCommunicationObjects.agentUrl = HttpUrl.get("http://localhost");
    sharedCommunicationObjects.agentHttpClient = new OkHttpClient.Builder().build();

    try {
      FeatureFlaggingSystem.start(sharedCommunicationObjects);
      assertNull(FeatureFlaggingGateway.getFlagEvalWriter());
    } finally {
      FeatureFlaggingSystem.stop();
    }
  }

  @Test
  @WithConfig(key = REMOTE_CONFIGURATION_ENABLED, value = "false")
  void testThatRemoteConfigIsRequired() {
    SharedCommunicationObjects sharedCommunicationObjects = mock(SharedCommunicationObjects.class);

    try {
      assertThrows(
          IllegalStateException.class,
          () -> FeatureFlaggingSystem.start(sharedCommunicationObjects));
    } finally {
      FeatureFlaggingSystem.stop();
    }
  }
}
