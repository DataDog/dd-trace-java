package com.datadog.featureflag;

import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.FEATURE_FLAGS_CONFIGURATION_SOURCE;
import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.FEATURE_FLAGS_ENABLED;
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
import static org.mockito.Mockito.verifyNoInteractions;

import datadog.trace.api.Config;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.FeatureFlaggingGateway.RuntimeMode;
import datadog.trace.api.featureflag.flagevaluation.FlagEvaluationWriter;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.junit.utils.config.WithConfigExtension;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(WithConfigExtension.class)
class StandaloneFeatureFlaggingSystemTest {

  @Test
  void manualRegistrationUsesDefaultDirectSource() {
    final StandaloneFeatureFlaggingSystem.SystemInitializer initializer =
        mock(StandaloneFeatureFlaggingSystem.SystemInitializer.class);
    assertTrue(StandaloneFeatureFlaggingSystem.start(initializer));
    verify(initializer).initialize(any(Config.class));
  }

  @Test
  @WithConfig(key = FEATURE_FLAGS_ENABLED, value = "false")
  @WithConfig(key = FEATURE_FLAGS_CONFIGURATION_SOURCE, value = "agentless")
  void explicitDisablePreventsRuntimeStartup() {
    final StandaloneFeatureFlaggingSystem.SystemInitializer initializer =
        mock(StandaloneFeatureFlaggingSystem.SystemInitializer.class);
    assertFalse(StandaloneFeatureFlaggingSystem.start(initializer));
    verifyNoInteractions(initializer);
    assertNull(FeatureFlaggingGateway.activeRuntime());
  }

  @Test
  void closingOneConsumerKeepsTheOtherConsumerActive() throws Exception {
    final StandaloneFeatureFlaggingSystem.SystemInitializer initializer =
        mock(StandaloneFeatureFlaggingSystem.SystemInitializer.class);
    final AutoCloseable first = StandaloneFeatureFlaggingSystem.acquire(initializer);
    final AutoCloseable second = StandaloneFeatureFlaggingSystem.acquire(initializer);
    first.close();
    first.close();
    assertSame(RuntimeMode.STANDALONE, FeatureFlaggingGateway.activeRuntime());
    verify(initializer).initialize(any(Config.class));
    second.close();
    assertNull(FeatureFlaggingGateway.activeRuntime());
  }

  @Test
  void closedGenerationDoesNotReleaseAReplacementRuntime() throws Exception {
    final StandaloneFeatureFlaggingSystem.SystemInitializer initializer =
        mock(StandaloneFeatureFlaggingSystem.SystemInitializer.class);
    final AutoCloseable old = StandaloneFeatureFlaggingSystem.acquire(initializer);
    StandaloneFeatureFlaggingSystem.stop();
    final AutoCloseable replacement = StandaloneFeatureFlaggingSystem.acquire(initializer);
    old.close();
    assertSame(RuntimeMode.STANDALONE, FeatureFlaggingGateway.activeRuntime());
    replacement.close();
    assertNull(FeatureFlaggingGateway.activeRuntime());
  }

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
  @WithConfig(key = "remote_configuration.enabled", value = "true")
  void startsStandaloneRemoteConfigWhenRequested() {
    final StandaloneFeatureFlaggingSystem.SystemInitializer initializer =
        mock(StandaloneFeatureFlaggingSystem.SystemInitializer.class);

    assertTrue(StandaloneFeatureFlaggingSystem.start(initializer));
    verify(initializer).initialize(any(Config.class));
    assertSame(RuntimeMode.STANDALONE, FeatureFlaggingGateway.activeRuntime());
  }

  @Test
  @WithConfig(key = FEATURE_FLAGS_CONFIGURATION_SOURCE, value = "remote_config")
  @WithConfig(key = "remote_configuration.enabled", value = "false")
  void rejectsRemoteConfigDisabledInsteadOfFallingBackToDirect() {
    final StandaloneFeatureFlaggingSystem.SystemInitializer initializer =
        mock(StandaloneFeatureFlaggingSystem.SystemInitializer.class);
    assertThrows(
        IllegalStateException.class, () -> StandaloneFeatureFlaggingSystem.start(initializer));
    verifyNoInteractions(initializer);
    assertNull(FeatureFlaggingGateway.activeRuntime());
  }

  @Test
  @WithConfig(key = FEATURE_FLAGS_ENABLED, value = "false")
  @WithConfig(key = FEATURE_FLAGS_CONFIGURATION_SOURCE, value = "remote_config")
  @WithConfig(key = "remote_configuration.enabled", value = "false")
  void globalDisablePreventsRemoteConfigStartup() {
    final StandaloneFeatureFlaggingSystem.SystemInitializer initializer =
        mock(StandaloneFeatureFlaggingSystem.SystemInitializer.class);
    assertFalse(StandaloneFeatureFlaggingSystem.start(initializer));
    verifyNoInteractions(initializer);
    assertNull(FeatureFlaggingGateway.activeRuntime());
  }

  @Test
  @WithConfig(key = FEATURE_FLAGS_CONFIGURATION_SOURCE, value = "remote_config")
  @WithConfig(key = "remote_configuration.enabled", value = "true")
  void remoteConfigDoesNotStartASecondRuntimeWhenAgentOwnsIt() {
    final StandaloneFeatureFlaggingSystem.SystemInitializer initializer =
        mock(StandaloneFeatureFlaggingSystem.SystemInitializer.class);
    assertTrue(FeatureFlaggingGateway.claimRuntime(RuntimeMode.AGENT));
    assertNull(StandaloneFeatureFlaggingSystem.acquire(initializer));
    verifyNoInteractions(initializer);
    assertSame(RuntimeMode.AGENT, FeatureFlaggingGateway.activeRuntime());
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

  @Test
  @WithConfig(key = FEATURE_FLAGS_CONFIGURATION_SOURCE, value = "agentless")
  void initializationErrorReleasesRuntimeOwnership() {
    final StandaloneFeatureFlaggingSystem.SystemInitializer initializer =
        mock(StandaloneFeatureFlaggingSystem.SystemInitializer.class);
    final AssertionError failure = new AssertionError("initialization failed");
    doThrow(failure).when(initializer).initialize(any(Config.class));

    assertSame(
        failure,
        assertThrows(
            AssertionError.class, () -> StandaloneFeatureFlaggingSystem.start(initializer)));

    assertNull(FeatureFlaggingGateway.activeRuntime());
  }

  @Test
  @WithConfig(key = FEATURE_FLAGS_CONFIGURATION_SOURCE, value = "agentless")
  void reportsStoppedWhenOwnershipChangesAfterStartup() {
    final StandaloneFeatureFlaggingSystem.SystemInitializer initializer =
        mock(StandaloneFeatureFlaggingSystem.SystemInitializer.class);
    assertTrue(StandaloneFeatureFlaggingSystem.start(initializer));
    FeatureFlaggingGateway.releaseRuntime(RuntimeMode.STANDALONE);
    assertTrue(FeatureFlaggingGateway.claimRuntime(RuntimeMode.AGENT));

    assertFalse(StandaloneFeatureFlaggingSystem.start(initializer));

    verify(initializer, times(1)).initialize(any(Config.class));
  }

  @Test
  void initializesAndStopsRuntimeComponents() {
    final ConfigurationSourceService configService = mock(ConfigurationSourceService.class);
    final ExposureWriter exposureWriter = mock(ExposureWriter.class);
    final FlagEvaluationWriter evalWriter = mock(FlagEvaluationWriter.class);

    StandaloneFeatureFlaggingSystem.initializeSystem(
        configService, exposureWriter, () -> evalWriter, true);

    verify(configService).init();
    verify(exposureWriter).init();
    verify(evalWriter).start();
    assertFalse(StandaloneFeatureFlaggingSystem.stop());
    verify(configService).close();
    verify(exposureWriter).close();
    verify(evalWriter).close();
  }

  @Test
  void stoppingInactiveStandaloneDoesNotClearAgentWriter() {
    FlagEvaluationWriter writer = mock(FlagEvaluationWriter.class);
    assertTrue(FeatureFlaggingGateway.claimRuntime(RuntimeMode.AGENT));
    FeatureFlaggingGateway.setFlagEvalWriter(writer);
    FeatureFlaggingGateway.setFlagEvaluationEnqueueEnabled(true);
    StandaloneFeatureFlaggingSystem.stop();
    assertSame(writer, FeatureFlaggingGateway.getFlagEvalWriter());
    assertTrue(FeatureFlaggingGateway.isFlagEvaluationEnqueueEnabled());
    assertSame(RuntimeMode.AGENT, FeatureFlaggingGateway.activeRuntime());
  }

  @Test
  void disablesEvaluationWriterWhenConfigured() {
    final ConfigurationSourceService configService = mock(ConfigurationSourceService.class);
    final ExposureWriter exposureWriter = mock(ExposureWriter.class);
    final Supplier<FlagEvaluationWriter> evalWriterFactory = mock(Supplier.class);

    StandaloneFeatureFlaggingSystem.initializeSystem(
        configService, exposureWriter, evalWriterFactory, false);

    verifyNoInteractions(evalWriterFactory);
    assertFalse(StandaloneFeatureFlaggingSystem.stop());
  }

  @Test
  void closesComponentsWhenInitializationFails() {
    final ConfigurationSourceService configService = mock(ConfigurationSourceService.class);
    final ExposureWriter exposureWriter = mock(ExposureWriter.class);
    final IllegalStateException failure = new IllegalStateException("config failed");
    doThrow(failure).when(configService).init();

    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () -> StandaloneFeatureFlaggingSystem.initialize(configService, exposureWriter)));

    verify(exposureWriter).close();
    verify(configService).close();
  }

  @Test
  void closesComponentsWhenExposureInitializationFails() {
    final ConfigurationSourceService configService = mock(ConfigurationSourceService.class);
    final ExposureWriter exposureWriter = mock(ExposureWriter.class);
    final AssertionError failure = new AssertionError("exposure failed");
    doThrow(failure).when(exposureWriter).init();

    assertSame(
        failure,
        assertThrows(
            AssertionError.class,
            () -> StandaloneFeatureFlaggingSystem.initialize(configService, exposureWriter)));

    verify(configService).init();
    verify(exposureWriter).close();
    verify(configService).close();
  }
}
