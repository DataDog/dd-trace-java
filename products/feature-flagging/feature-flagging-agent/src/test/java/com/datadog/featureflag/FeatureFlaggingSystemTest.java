package com.datadog.featureflag;

import static datadog.trace.api.config.RemoteConfigConfig.REMOTE_CONFIGURATION_ENABLED;
import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.FEATURE_FLAGS_CONFIGURATION_SOURCE;
import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.FEATURE_FLAGS_ENABLED;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.remoteconfig.Capabilities;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.remoteconfig.Product;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.test.junit.utils.config.WithConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@WithConfig(key = FEATURE_FLAGS_ENABLED, value = "true")
class FeatureFlaggingSystemTest {
  @AfterEach
  void stop() {
    FeatureFlaggingSystem.stop();
  }

  @Test
  void startRegistersTheBackendAndStopUnregistersIt() {
    FeatureFlaggingSystem.start(mock(SharedCommunicationObjects.class));
    assertInstanceOf(FeatureFlagsBackend.class, FeatureFlaggingGateway.backend());

    FeatureFlaggingSystem.stop();
    assertNull(FeatureFlaggingGateway.backend());
  }

  @Test
  void startIsIdempotent() {
    final SharedCommunicationObjects sco = mock(SharedCommunicationObjects.class);
    FeatureFlaggingSystem.start(sco);
    final FeatureFlaggingGateway.Backend backend = FeatureFlaggingGateway.backend();
    assertInstanceOf(FeatureFlagsBackend.class, backend);

    FeatureFlaggingSystem.start(sco);
    assertSame(backend, FeatureFlaggingGateway.backend());
  }

  @Test
  @WithConfig(key = FEATURE_FLAGS_CONFIGURATION_SOURCE, value = "remote_config")
  @WithConfig(key = REMOTE_CONFIGURATION_ENABLED, value = "true")
  void failedStartRollsBackTheRemoteConfigRegistration() {
    final SharedCommunicationObjects sco = mock(SharedCommunicationObjects.class);
    final ConfigurationPoller poller = mock(ConfigurationPoller.class);
    when(sco.configurationPoller(any())).thenReturn(poller);
    doThrow(new IllegalStateException("poller failed")).when(poller).start();

    assertThrows(IllegalStateException.class, () -> FeatureFlaggingSystem.start(sco));

    assertNull(FeatureFlaggingGateway.backend());
    verify(poller).removeCapabilities(Capabilities.CAPABILITY_FFE_FLAG_CONFIGURATION_RULES);
    verify(poller).removeListeners(Product.FFE_FLAGS);
  }

  @Test
  @WithConfig(key = FEATURE_FLAGS_ENABLED, value = "false")
  void startDoesNothingWhenFeatureFlagsIsDisabled() {
    FeatureFlaggingSystem.start(mock(SharedCommunicationObjects.class));
    assertNull(FeatureFlaggingGateway.backend());
  }
}
