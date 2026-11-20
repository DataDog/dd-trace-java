package com.datadog.featureflag;

import static datadog.trace.api.config.FeatureFlaggingConfig.FEATURE_FLAGS_CONFIGURATION_SOURCE;
import static datadog.trace.api.config.FeatureFlaggingConfig.FEATURE_FLAGS_ENABLED;
import static datadog.trace.api.config.RemoteConfigConfig.REMOTE_CONFIGURATION_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_ENABLED;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import java.lang.reflect.Field;
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
  @WithConfig(key = TRACE_ENABLED, value = "false")
  void stopsFromAShutdownHookOnceTheSdkSubscribesWhenTracingIsDisabled() {
    FeatureFlaggingSystem.start(mock(SharedCommunicationObjects.class));
    assertFalse(
        FeatureFlaggingSystem.isShutdownHookRegistered(), "no hook while the SDK is unused");

    FeatureFlaggingGateway.backend().subscribeShutdown(() -> {});
    assertTrue(FeatureFlaggingSystem.isShutdownHookRegistered());

    FeatureFlaggingSystem.stop();
    assertFalse(FeatureFlaggingSystem.isShutdownHookRegistered());
  }

  @Test
  @WithConfig(key = TRACE_ENABLED, value = "false")
  void shutdownHookFlushesListenersAndStopsTheBackend() throws Exception {
    FeatureFlaggingSystem.start(mock(SharedCommunicationObjects.class));
    final Runnable listener = mock(Runnable.class);
    FeatureFlaggingGateway.backend().subscribeShutdown(listener);

    final Field field = FeatureFlaggingSystem.class.getDeclaredField("SHUTDOWN_HOOK");
    field.setAccessible(true);
    final Thread hook = (Thread) field.get(null);
    // Run the real hook without shutting down the test JVM.
    assertTrue(Runtime.getRuntime().removeShutdownHook(hook));
    hook.start();
    hook.join(5000);

    assertFalse(hook.isAlive(), "the shutdown hook must finish");
    assertNull(FeatureFlaggingGateway.backend());
    assertFalse(FeatureFlaggingSystem.isShutdownHookRegistered());
    verify(listener).run();
  }

  @Test
  @WithConfig(key = TRACE_ENABLED, value = "false")
  void lateShutdownSubscriptionDoesNotRegisterAHookAfterStop() throws Exception {
    FeatureFlaggingSystem.start(mock(SharedCommunicationObjects.class));
    final FeatureFlaggingGateway.Backend backend = FeatureFlaggingGateway.backend();
    FeatureFlaggingSystem.stop();

    try (final AutoCloseable ignored = backend.subscribeShutdown(() -> {})) {
      assertNull(FeatureFlaggingGateway.backend());
      assertFalse(FeatureFlaggingSystem.isShutdownHookRegistered());
    }
  }

  @Test
  void keepsAShutdownHookWhenTracingIsEnabled() {
    // The tracing shutdown instrumentation can be disabled on its own.
    FeatureFlaggingSystem.start(mock(SharedCommunicationObjects.class));
    FeatureFlaggingGateway.backend().subscribeShutdown(() -> {});
    assertTrue(FeatureFlaggingSystem.isShutdownHookRegistered());
  }

  @Test
  @WithConfig(key = FEATURE_FLAGS_ENABLED, value = "false")
  void startDoesNothingWhenFeatureFlagsIsDisabled() {
    FeatureFlaggingSystem.start(mock(SharedCommunicationObjects.class));
    assertNull(FeatureFlaggingGateway.backend());
  }
}
