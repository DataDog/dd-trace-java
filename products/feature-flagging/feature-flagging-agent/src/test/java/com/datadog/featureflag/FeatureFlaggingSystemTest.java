package com.datadog.featureflag;

import static datadog.trace.api.config.RemoteConfigConfig.REMOTE_CONFIGURATION_ENABLED;
import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.FEATURE_FLAGS_CONFIGURATION_SOURCE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
import datadog.trace.junit.utils.config.WithConfig;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

class FeatureFlaggingSystemTest {

  @Test
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

    FeatureFlaggingSystem.start(sharedCommunicationObjects);
    FeatureFlaggingSystem.start(sharedCommunicationObjects);

    verify(poller).addCapabilities(Capabilities.CAPABILITY_FFE_FLAG_CONFIGURATION_RULES);
    verify(poller).addListener(eq(Product.FFE_FLAGS), any(ConfigurationDeserializer.class), any());
    verify(poller).start();

    FeatureFlaggingSystem.stop();
    FeatureFlaggingSystem.stop();

    verify(poller).removeCapabilities(Capabilities.CAPABILITY_FFE_FLAG_CONFIGURATION_RULES);
    verify(poller).removeListeners(Product.FFE_FLAGS);
    verify(poller).stop();
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
      FeatureFlaggingSystem.stop();
    }
  }

  @Test
  @WithConfig(key = FEATURE_FLAGS_CONFIGURATION_SOURCE, value = "agentless")
  @WithConfig(key = REMOTE_CONFIGURATION_ENABLED, value = "false")
  void agentlessConfigurationSourceUsesHttpServiceWithoutRemoteConfig() {
    assertInstanceOf(
        AgentlessConfigurationSource.class,
        FeatureFlaggingSystem.createConfigurationSourceService(
            sharedCommunicationObjects(), Config.get()));
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
  void invalidConfigurationSourceUsesAgentlessDefault() {
    assertInstanceOf(
        AgentlessConfigurationSource.class,
        FeatureFlaggingSystem.createConfigurationSourceService(
            sharedCommunicationObjects(), Config.get()));
  }

  @Test
  void rejectsUnsupportedNormalizedConfigurationSource() {
    Config config = mock(Config.class);
    when(config.getFeatureFlaggingConfigurationSource()).thenReturn("invalid");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            FeatureFlaggingSystem.createConfigurationSourceService(
                sharedCommunicationObjects(), config));
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
    } finally {
      FeatureFlaggingSystem.stop();
    }
  }

  @Test
  void initializationFailureClosesConfigurationSourceAndExposureWriter() {
    ConfigurationSourceService configService = mock(ConfigurationSourceService.class);
    ExposureWriter exposureWriter = mock(ExposureWriter.class);
    doThrow(new IllegalStateException("exposure init failed")).when(exposureWriter).init();

    assertThrows(
        IllegalStateException.class,
        () -> FeatureFlaggingSystem.initialize(configService, exposureWriter));

    verify(configService).init();
    verify(configService).close();
    verify(exposureWriter).close();
  }

  @Test
  void initializationFailureWithoutConfigurationSourceClosesExposureWriter() {
    ExposureWriter exposureWriter = mock(ExposureWriter.class);
    doThrow(new IllegalStateException("exposure init failed")).when(exposureWriter).init();

    assertThrows(
        IllegalStateException.class, () -> FeatureFlaggingSystem.initialize(null, exposureWriter));

    verify(exposureWriter).close();
  }

  @Test
  void initializationFailureClosesConfigurationSourceWhenExposureWriterCloseFails() {
    ConfigurationSourceService configService = mock(ConfigurationSourceService.class);
    ExposureWriter exposureWriter = mock(ExposureWriter.class);
    doThrow(new IllegalStateException("exposure init failed")).when(exposureWriter).init();
    doThrow(new IllegalArgumentException("exposure close failed")).when(exposureWriter).close();

    assertThrows(
        IllegalArgumentException.class,
        () -> FeatureFlaggingSystem.initialize(configService, exposureWriter));

    verify(configService).close();
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
