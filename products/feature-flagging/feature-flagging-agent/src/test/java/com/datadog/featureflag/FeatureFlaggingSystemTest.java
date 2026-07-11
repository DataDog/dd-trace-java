package com.datadog.featureflag;

import static datadog.trace.api.config.FeatureFlaggingConfig.FEATURE_FLAGS_CONFIGURATION_SOURCE;
import static datadog.trace.api.config.RemoteConfigConfig.REMOTE_CONFIGURATION_ENABLED;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import datadog.trace.api.featureflag.flagevaluation.FlagEvaluationWriter;
import datadog.trace.junit.utils.config.WithConfig;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FeatureFlaggingSystemTest {

  @AfterEach
  void resetFlagEvaluationGateway() {
    FeatureFlaggingSystem.stop();
    FeatureFlaggingGateway.setFlagEvalWriter(null);
    FeatureFlaggingGateway.setFlagEvaluationEnqueueEnabled(true);
  }

  @Test
  @WithConfig(key = FeatureFlaggingConfig.FLAGGING_EVALUATION_COUNTS_ENABLED, value = "true")
  @WithConfig(key = FEATURE_FLAGS_CONFIGURATION_SOURCE, value = "remote_config")
  @WithConfig(key = REMOTE_CONFIGURATION_ENABLED, value = "true")
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
    FeatureFlaggingGateway.setFlagEvaluationEnqueueEnabled(false);

    FeatureFlaggingSystem.start(sharedCommunicationObjects);

    verify(poller).addCapabilities(Capabilities.CAPABILITY_FFE_FLAG_CONFIGURATION_RULES);
    verify(poller).addListener(eq(Product.FFE_FLAGS), any(ConfigurationDeserializer.class), any());
    verify(poller).start();
    assertTrue(FeatureFlaggingGateway.isFlagEvaluationEnqueueEnabled());
    assertNotNull(FeatureFlaggingGateway.getFlagEvalWriter());

    FeatureFlaggingSystem.shutdown();
    assertFalse(FeatureFlaggingGateway.isFlagEvaluationEnqueueEnabled());
    assertNull(FeatureFlaggingGateway.getFlagEvalWriter());

    verify(poller).removeCapabilities(Capabilities.CAPABILITY_FFE_FLAG_CONFIGURATION_RULES);
    verify(poller).removeListeners(Product.FFE_FLAGS);
    verify(poller).stop();
  }

  @Test
  @WithConfig(key = FeatureFlaggingConfig.FLAGGING_EVALUATION_COUNTS_ENABLED, value = "false")
  @WithConfig(key = FEATURE_FLAGS_CONFIGURATION_SOURCE, value = "offline")
  void testFlagEvaluationWriterCanBeDisabled() {
    SharedCommunicationObjects sharedCommunicationObjects = sharedCommunicationObjects();
    FeatureFlaggingGateway.setFlagEvaluationEnqueueEnabled(true);
    FeatureFlaggingGateway.setFlagEvalWriter(mock(FlagEvaluationWriter.class));

    try {
      FeatureFlaggingSystem.start(sharedCommunicationObjects);
      assertFalse(FeatureFlaggingGateway.isFlagEvaluationEnqueueEnabled());
      assertNull(FeatureFlaggingGateway.getFlagEvalWriter());
    } finally {
      FeatureFlaggingSystem.shutdown();
    }
  }

  @Test
  void testFeatureFlagSystemShutdownClearsGatewayState() {
    FeatureFlaggingGateway.setFlagEvaluationEnqueueEnabled(true);
    FeatureFlaggingGateway.setFlagEvalWriter(mock(FlagEvaluationWriter.class));

    FeatureFlaggingSystem.shutdown();

    assertFalse(FeatureFlaggingGateway.isFlagEvaluationEnqueueEnabled());
    assertNull(FeatureFlaggingGateway.getFlagEvalWriter());
  }

  @Test
  @WithConfig(key = FEATURE_FLAGS_CONFIGURATION_SOURCE, value = "remote_config")
  @WithConfig(key = REMOTE_CONFIGURATION_ENABLED, value = "false")
  void testThatRemoteConfigIsRequired() {
    SharedCommunicationObjects sharedCommunicationObjects = mock(SharedCommunicationObjects.class);

    try {
      assertThrows(
          IllegalStateException.class,
          () -> FeatureFlaggingSystem.start(sharedCommunicationObjects));
    } finally {
      FeatureFlaggingSystem.shutdown();
    }
  }

  @Test
  @WithConfig(key = FEATURE_FLAGS_CONFIGURATION_SOURCE, value = "agentless")
  @WithConfig(
      key = FeatureFlaggingConfig.FEATURE_FLAGS_CONFIGURATION_SOURCE_AGENTLESS_BASE_URL,
      value = "http://localhost:1/config")
  @WithConfig(key = REMOTE_CONFIGURATION_ENABLED, value = "false")
  void agentlessConfigurationSourceUsesHttpServiceWithoutRemoteConfig() {
    assertInstanceOf(
        AgentlessConfigurationSource.class,
        FeatureFlaggingSystem.createConfigurationSourceService(
            sharedCommunicationObjects(), Config.get()));
  }

  @Test
  @WithConfig(key = FEATURE_FLAGS_CONFIGURATION_SOURCE, value = "agentless")
  @WithConfig(
      key = FeatureFlaggingConfig.FEATURE_FLAGS_CONFIGURATION_SOURCE_AGENTLESS_BASE_URL,
      value = "http://localhost:1/config")
  @WithConfig(key = REMOTE_CONFIGURATION_ENABLED, value = "false")
  @WithConfig(key = FeatureFlaggingConfig.FLAGGING_EVALUATION_COUNTS_ENABLED, value = "true")
  void agentlessConfigurationSourceStartsTelemetryWritersWithoutRemoteConfig() {
    try {
      FeatureFlaggingSystem.start(sharedCommunicationObjects());

      assertTrue(FeatureFlaggingGateway.isFlagEvaluationEnqueueEnabled());
      assertNotNull(FeatureFlaggingGateway.getFlagEvalWriter());
    } finally {
      FeatureFlaggingSystem.shutdown();
    }
  }

  @Test
  @WithConfig(key = FEATURE_FLAGS_CONFIGURATION_SOURCE, value = "remote_config")
  @WithConfig(key = REMOTE_CONFIGURATION_ENABLED, value = "true")
  void explicitRemoteConfigUsesRemoteConfigService() {
    SharedCommunicationObjects sharedCommunicationObjects = sharedCommunicationObjects();
    when(sharedCommunicationObjects.configurationPoller(any(Config.class)))
        .thenReturn(mock(ConfigurationPoller.class));

    assertInstanceOf(
        RemoteConfigServiceImpl.class,
        FeatureFlaggingSystem.createConfigurationSourceService(
            sharedCommunicationObjects, Config.get()));
  }

  @Test
  @WithConfig(key = FEATURE_FLAGS_CONFIGURATION_SOURCE, value = "invalid")
  void invalidConfigurationSourceFailsBeforeStartingNetworkSource() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            FeatureFlaggingSystem.createConfigurationSourceService(
                sharedCommunicationObjects(), Config.get()));
  }

  @Test
  @WithConfig(key = FEATURE_FLAGS_CONFIGURATION_SOURCE, value = "offline")
  void offlineConfigurationSourceDoesNotStartNetworkSource() {
    assertNull(
        FeatureFlaggingSystem.createConfigurationSourceService(
            sharedCommunicationObjects(), Config.get()));
  }

  @Test
  @WithConfig(key = FEATURE_FLAGS_CONFIGURATION_SOURCE, value = "offline")
  void startWithOfflineConfigurationSourceSkipsConfigService() {
    try {
      assertDoesNotThrow(() -> FeatureFlaggingSystem.start(sharedCommunicationObjects()));
      assertTrue(FeatureFlaggingGateway.isFlagEvaluationEnqueueEnabled());
      assertNotNull(FeatureFlaggingGateway.getFlagEvalWriter());
    } finally {
      FeatureFlaggingSystem.stop();
    }
  }

  private static SharedCommunicationObjects sharedCommunicationObjects() {
    DDAgentFeaturesDiscovery discovery = mock(DDAgentFeaturesDiscovery.class);
    when(discovery.supportsEvpProxy()).thenReturn(true);
    when(discovery.getEvpProxyEndpoint()).thenReturn("/evp_proxy/");
    SharedCommunicationObjects sharedCommunicationObjects = mock(SharedCommunicationObjects.class);
    when(sharedCommunicationObjects.featuresDiscovery(any(Config.class))).thenReturn(discovery);
    sharedCommunicationObjects.agentUrl = HttpUrl.get("http://localhost");
    sharedCommunicationObjects.agentHttpClient = new OkHttpClient.Builder().build();
    return sharedCommunicationObjects;
  }
}
