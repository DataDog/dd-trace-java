package com.datadog.featureflag;

import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.FEATURE_FLAGS_ENABLED;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.test.junit.utils.config.WithConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FeatureFlaggingSystemTest {
  @AfterEach
  void stop() {
    FeatureFlaggingSystem.stop();
  }

  @Test
  @WithConfig(key = FEATURE_FLAGS_ENABLED, value = "true")
  void startRegistersTheBackendAndStopUnregistersIt() {
    FeatureFlaggingSystem.start(mock(SharedCommunicationObjects.class));
    assertInstanceOf(FeatureFlagsBackend.class, FeatureFlaggingGateway.backend());

    FeatureFlaggingSystem.stop();
    assertNull(FeatureFlaggingGateway.backend());
  }

  @Test
  @WithConfig(key = FEATURE_FLAGS_ENABLED, value = "true")
  void startIsIdempotent() {
    final SharedCommunicationObjects sco = mock(SharedCommunicationObjects.class);
    FeatureFlaggingSystem.start(sco);
    final FeatureFlaggingGateway.Backend backend = FeatureFlaggingGateway.backend();
    assertInstanceOf(FeatureFlagsBackend.class, backend);

    FeatureFlaggingSystem.start(sco);
    assertSame(backend, FeatureFlaggingGateway.backend());
  }

  @Test
  @WithConfig(key = FEATURE_FLAGS_ENABLED, value = "false")
  void startDoesNothingWhenFeatureFlagsIsDisabled() {
    FeatureFlaggingSystem.start(mock(SharedCommunicationObjects.class));
    assertNull(FeatureFlaggingGateway.backend());
  }
}
