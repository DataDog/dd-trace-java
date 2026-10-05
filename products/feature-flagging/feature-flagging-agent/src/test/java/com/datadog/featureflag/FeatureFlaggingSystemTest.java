package com.datadog.featureflag;

import static datadog.trace.api.config.RemoteConfigConfig.REMOTE_CONFIGURATION_ENABLED;
import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.FEATURE_FLAGS_CONFIGURATION_SOURCE;
import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.FEATURE_FLAGS_CONFIGURATION_SOURCE_AGENTLESS_BASE_URL;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.remoteconfig.Capabilities;
import datadog.remoteconfig.ConfigurationDeserializer;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.remoteconfig.Product;
import datadog.trace.api.Config;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.config.FeatureFlaggingConfig;
import datadog.trace.api.featureflag.flagevaluation.FlagEvaluationWriter;
import datadog.trace.test.junit.utils.config.WithConfig;
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
  @WithConfig(key = FEATURE_FLAGS_CONFIGURATION_SOURCE, value = "agentless")
  @WithConfig(
      key = FEATURE_FLAGS_CONFIGURATION_SOURCE_AGENTLESS_BASE_URL,
      value = "http://127.0.0.1:1")
  void agentlessStartWaitsForApplicationProviderActivationWithoutPreparingDelivery() {
    SharedCommunicationObjects sharedCommunicationObjects = sharedCommunicationObjects();
    clearInvocations(sharedCommunicationObjects);

    try {
      FeatureFlaggingSystem.start(sharedCommunicationObjects);

      assertTrue(FeatureFlaggingSystem.isAwaitingApplicationActivation());
      assertFalse(FeatureFlaggingSystem.isExposureWriterStarted());
      assertFalse(FeatureFlaggingSystem.isConfigurationSourceStarted());
      verifyNoInteractions(sharedCommunicationObjects);
    } finally {
      FeatureFlaggingSystem.stop();
    }

    assertFalse(FeatureFlaggingSystem.isAwaitingApplicationActivation());
    assertFalse(FeatureFlaggingSystem.isExposureWriterStarted());
    assertFalse(FeatureFlaggingSystem.isConfigurationSourceStarted());
  }

  @Test
  @WithConfig(key = FEATURE_FLAGS_CONFIGURATION_SOURCE, value = "agentless")
  void agentlessActivationInitializesSystemOnce() {
    final SharedCommunicationObjects sharedCommunicationObjects = sharedCommunicationObjects();
    final FeatureFlaggingSystem.SystemInitializer systemInitializer =
        mock(FeatureFlaggingSystem.SystemInitializer.class);

    FeatureFlaggingSystem.start(sharedCommunicationObjects, systemInitializer);

    verifyNoInteractions(systemInitializer);
    assertTrue(FeatureFlaggingSystem.isAwaitingApplicationActivation());

    FeatureFlaggingGateway.activate();
    FeatureFlaggingGateway.activate();

    verify(systemInitializer).initialize(eq(sharedCommunicationObjects), any(Config.class));
    assertFalse(FeatureFlaggingSystem.isAwaitingApplicationActivation());
  }

  @Test
  @WithConfig(key = FEATURE_FLAGS_CONFIGURATION_SOURCE, value = "agentless")
  void agentlessInitializationFailureCleansUpAndAllowsRetry() {
    final SharedCommunicationObjects sharedCommunicationObjects = sharedCommunicationObjects();
    final FeatureFlaggingSystem.SystemInitializer failedInitializer =
        mock(FeatureFlaggingSystem.SystemInitializer.class);
    final IllegalStateException initializationFailure =
        new IllegalStateException("system initialization failed");
    doThrow(initializationFailure)
        .when(failedInitializer)
        .initialize(any(SharedCommunicationObjects.class), any(Config.class));

    FeatureFlaggingSystem.start(sharedCommunicationObjects, failedInitializer);

    final IllegalStateException thrown =
        assertThrows(IllegalStateException.class, FeatureFlaggingGateway::activate);

    assertSame(initializationFailure, thrown);
    assertFalse(FeatureFlaggingSystem.isAwaitingApplicationActivation());
    assertFalse(FeatureFlaggingSystem.isExposureWriterStarted());
    assertFalse(FeatureFlaggingSystem.isConfigurationSourceStarted());

    final FeatureFlaggingSystem.SystemInitializer retryInitializer =
        mock(FeatureFlaggingSystem.SystemInitializer.class);
    FeatureFlaggingSystem.start(sharedCommunicationObjects, retryInitializer);
    FeatureFlaggingGateway.activate();

    verify(retryInitializer).initialize(eq(sharedCommunicationObjects), any(Config.class));
  }

  @Test
  @WithConfig(key = FEATURE_FLAGS_CONFIGURATION_SOURCE, value = "agentless")
  @WithConfig(
      key = FEATURE_FLAGS_CONFIGURATION_SOURCE_AGENTLESS_BASE_URL,
      value = "http://127.0.0.1:1")
  void agentlessStopRemovesPendingApplicationProviderActivation() {
    SharedCommunicationObjects sharedCommunicationObjects = sharedCommunicationObjects();
    clearInvocations(sharedCommunicationObjects);

    try {
      FeatureFlaggingSystem.start(sharedCommunicationObjects);
      assertTrue(FeatureFlaggingSystem.isAwaitingApplicationActivation());

      FeatureFlaggingSystem.stop();
      clearInvocations(sharedCommunicationObjects);
      FeatureFlaggingGateway.activate();

      assertFalse(FeatureFlaggingSystem.isAwaitingApplicationActivation());
      verifyNoInteractions(sharedCommunicationObjects);
    } finally {
      FeatureFlaggingSystem.stop();
    }
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
    FeatureFlaggingSystem.start(sharedCommunicationObjects);

    verify(poller).addCapabilities(Capabilities.CAPABILITY_FFE_FLAG_CONFIGURATION_RULES);
    verify(poller).addListener(eq(Product.FFE_FLAGS), any(ConfigurationDeserializer.class), any());
    verify(poller).start();
    assertTrue(FeatureFlaggingGateway.isFlagEvaluationEnqueueEnabled());
    assertNotNull(FeatureFlaggingGateway.getFlagEvalWriter());

    FeatureFlaggingSystem.stop();
    assertFalse(FeatureFlaggingGateway.isFlagEvaluationEnqueueEnabled());
    assertNull(FeatureFlaggingGateway.getFlagEvalWriter());
    // stop() is idempotent: a second call must be a safe no-op.
    FeatureFlaggingSystem.stop();

    verify(poller).removeCapabilities(Capabilities.CAPABILITY_FFE_FLAG_CONFIGURATION_RULES);
    verify(poller).removeListeners(Product.FFE_FLAGS);
    verify(poller).stop();
  }

  @Test
  @WithConfig(key = FeatureFlaggingConfig.FLAGGING_EVALUATION_COUNTS_ENABLED, value = "false")
  @WithConfig(key = FEATURE_FLAGS_CONFIGURATION_SOURCE, value = "agentless")
  @WithConfig(
      key = FeatureFlaggingConfig.FEATURE_FLAGS_CONFIGURATION_SOURCE_AGENTLESS_BASE_URL,
      value = "http://localhost:1/config")
  void testFlagEvaluationWriterCanBeDisabled() {
    SharedCommunicationObjects sharedCommunicationObjects = sharedCommunicationObjects();
    FeatureFlaggingGateway.setFlagEvaluationEnqueueEnabled(true);
    FeatureFlaggingGateway.setFlagEvalWriter(mock(FlagEvaluationWriter.class));

    try {
      FeatureFlaggingSystem.start(sharedCommunicationObjects);
      // Agentless defers initialization until the application provider activates.
      FeatureFlaggingGateway.activate();

      assertFalse(FeatureFlaggingGateway.isFlagEvaluationEnqueueEnabled());
      assertNull(FeatureFlaggingGateway.getFlagEvalWriter());
    } finally {
      FeatureFlaggingSystem.stop();
    }
  }

  @Test
  void testFeatureFlagSystemShutdownClearsGatewayState() {
    FeatureFlaggingGateway.setFlagEvaluationEnqueueEnabled(true);
    FeatureFlaggingGateway.setFlagEvalWriter(mock(FlagEvaluationWriter.class));

    FeatureFlaggingSystem.stop();

    assertFalse(FeatureFlaggingGateway.isFlagEvaluationEnqueueEnabled());
    assertNull(FeatureFlaggingGateway.getFlagEvalWriter());
  }

  @Test
  @WithConfig(key = FEATURE_FLAGS_CONFIGURATION_SOURCE, value = "remote_config")
  @WithConfig(key = REMOTE_CONFIGURATION_ENABLED, value = "false")
  void failedStartRollsBackPartiallyInitializedState() {
    SharedCommunicationObjects sharedCommunicationObjects = mock(SharedCommunicationObjects.class);

    assertThrows(
        IllegalStateException.class, () -> FeatureFlaggingSystem.start(sharedCommunicationObjects));

    // A failed start must leave nothing behind: no listener awaiting activation, no gateway
    // writer, and STARTED cleared so a later start() is not swallowed as "already started".
    assertFalse(FeatureFlaggingSystem.isAwaitingApplicationActivation());
    assertNull(FeatureFlaggingGateway.getFlagEvalWriter());
    assertFalse(FeatureFlaggingGateway.isFlagEvaluationEnqueueEnabled());
    assertThrows(
        IllegalStateException.class, () -> FeatureFlaggingSystem.start(sharedCommunicationObjects));
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
      // Agentless defers initialization until the application provider activates.
      FeatureFlaggingGateway.activate();

      assertTrue(FeatureFlaggingSystem.isExposureWriterStarted());
      assertTrue(FeatureFlaggingSystem.isConfigurationSourceStarted());
      assertTrue(FeatureFlaggingGateway.isFlagEvaluationEnqueueEnabled());
      assertNotNull(FeatureFlaggingGateway.getFlagEvalWriter());
    } finally {
      FeatureFlaggingSystem.stop();
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
  @WithConfig(key = FEATURE_FLAGS_CONFIGURATION_SOURCE, value = "offline")
  void offlineConfigurationSourceDoesNotStartNetworkSource() {
    assertNull(
        FeatureFlaggingSystem.createConfigurationSourceService(
            sharedCommunicationObjects(), Config.get()));
  }

  @Test
  @WithConfig(key = FEATURE_FLAGS_CONFIGURATION_SOURCE, value = "invalid")
  void invalidConfigurationSourceDoesNotStartNetworkSource() {
    assertNull(
        FeatureFlaggingSystem.createConfigurationSourceService(
            sharedCommunicationObjects(), Config.get()));
  }

  @Test
  void unsupportedNormalizedConfigurationSourceDoesNotStartNetworkSource() {
    Config config = mock(Config.class);
    when(config.getFeatureFlaggingConfigurationSource()).thenReturn("invalid");

    assertNull(
        FeatureFlaggingSystem.createConfigurationSourceService(
            sharedCommunicationObjects(), config));
  }

  @Test
  @WithConfig(key = FEATURE_FLAGS_CONFIGURATION_SOURCE, value = "offline")
  void startWithOfflineConfigurationSourceDisablesSystem() {
    SharedCommunicationObjects sharedCommunicationObjects = mock(SharedCommunicationObjects.class);

    try {
      assertDoesNotThrow(() -> FeatureFlaggingSystem.start(sharedCommunicationObjects));
      verifyNoInteractions(sharedCommunicationObjects);
    } finally {
      FeatureFlaggingSystem.stop();
    }
  }

  @Test
  @WithConfig(key = FEATURE_FLAGS_CONFIGURATION_SOURCE, value = "invalid")
  void startWithInvalidConfigurationSourceDisablesSystem() {
    SharedCommunicationObjects sharedCommunicationObjects = mock(SharedCommunicationObjects.class);

    try {
      assertDoesNotThrow(() -> FeatureFlaggingSystem.start(sharedCommunicationObjects));
      verifyNoInteractions(sharedCommunicationObjects);
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
    return sharedCommunicationObjects(discovery);
  }

  private static SharedCommunicationObjects sharedCommunicationObjects(
      final DDAgentFeaturesDiscovery discovery) {
    SharedCommunicationObjects sharedCommunicationObjects = mock(SharedCommunicationObjects.class);
    when(sharedCommunicationObjects.featuresDiscovery(any(Config.class))).thenReturn(discovery);
    sharedCommunicationObjects.agentUrl = HttpUrl.get("http://localhost");
    sharedCommunicationObjects.agentHttpClient = new OkHttpClient.Builder().build();
    return sharedCommunicationObjects;
  }
}
