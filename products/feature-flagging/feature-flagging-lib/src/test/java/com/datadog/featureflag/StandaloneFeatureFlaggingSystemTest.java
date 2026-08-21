package com.datadog.featureflag;

import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.FEATURE_FLAGS_CONFIGURATION_SOURCE;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import datadog.trace.api.Config;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.FeatureFlaggingGateway.RuntimeMode;
import datadog.trace.test.junit.utils.config.WithConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class StandaloneFeatureFlaggingSystemTest {

  @AfterEach
  void tearDown() {
    StandaloneFeatureFlaggingSystem.stop();
    FeatureFlaggingGateway.releaseRuntime(RuntimeMode.AGENT);
    FeatureFlaggingGateway.setFlagEvaluationEnqueueEnabled(true);
  }

  @Test
  @WithConfig(key = FEATURE_FLAGS_CONFIGURATION_SOURCE, value = "agentless")
  void startsOnceAndClaimsStandaloneRuntime() {
    final StandaloneFeatureFlaggingSystem.SystemInitializer initializer =
        mock(StandaloneFeatureFlaggingSystem.SystemInitializer.class);

    assertTrue(StandaloneFeatureFlaggingSystem.start(initializer));
    assertTrue(StandaloneFeatureFlaggingSystem.start(initializer));

    verify(initializer, times(1)).initialize(any(Config.class));
    assertSame(RuntimeMode.STANDALONE, FeatureFlaggingGateway.activeRuntime());
  }

  @Test
  @WithConfig(key = FEATURE_FLAGS_CONFIGURATION_SOURCE, value = "remote_config")
  void leavesRemoteConfigForTheAgentRuntime() {
    final StandaloneFeatureFlaggingSystem.SystemInitializer initializer =
        mock(StandaloneFeatureFlaggingSystem.SystemInitializer.class);

    assertFalse(StandaloneFeatureFlaggingSystem.start(initializer));

    verify(initializer, never()).initialize(any(Config.class));
    assertNull(FeatureFlaggingGateway.activeRuntime());
  }

  @Test
  @WithConfig(key = FEATURE_FLAGS_CONFIGURATION_SOURCE, value = "agentless")
  void doesNotReplaceAnAgentRuntime() {
    final StandaloneFeatureFlaggingSystem.SystemInitializer initializer =
        mock(StandaloneFeatureFlaggingSystem.SystemInitializer.class);
    assertTrue(FeatureFlaggingGateway.claimRuntime(RuntimeMode.AGENT));

    assertFalse(StandaloneFeatureFlaggingSystem.start(initializer));

    verify(initializer, never()).initialize(any(Config.class));
    assertSame(RuntimeMode.AGENT, FeatureFlaggingGateway.activeRuntime());
  }

  @Test
  @WithConfig(key = FEATURE_FLAGS_CONFIGURATION_SOURCE, value = "agentless")
  void initializationFailureReleasesRuntimeOwnership() {
    final StandaloneFeatureFlaggingSystem.SystemInitializer initializer =
        mock(StandaloneFeatureFlaggingSystem.SystemInitializer.class);
    final IllegalStateException failure = new IllegalStateException("initialization failed");
    doThrow(failure).when(initializer).initialize(any(Config.class));

    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class, () -> StandaloneFeatureFlaggingSystem.start(initializer)));

    assertNull(FeatureFlaggingGateway.activeRuntime());
  }
}
