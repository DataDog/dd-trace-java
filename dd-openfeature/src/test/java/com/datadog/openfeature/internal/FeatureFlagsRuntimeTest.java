package com.datadog.openfeature.internal;

import static java.util.Collections.emptyMap;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.datadog.openfeature.internal.bridge.v1.AgentBridge;
import com.datadog.openfeature.internal.bridge.v1.ConfigurationSource;
import com.datadog.openfeature.internal.bridge.v1.EventTransport;
import com.datadog.openfeature.internal.bridge.v1.HealthMetrics;
import com.datadog.openfeature.internal.bridge.v1.ShutdownSource;
import com.datadog.openfeature.internal.bridge.v1.SpanEnricher;
import com.datadog.openfeature.internal.config.TestSettings;
import com.datadog.openfeature.internal.flagevaluation.FlagEvalEvent;
import com.datadog.openfeature.internal.ufc.ServerConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class FeatureFlagsRuntimeTest {
  private static final byte[] EMPTY_UFC =
      "{\"environment\":{\"name\":\"Test\"},\"flags\":{}}".getBytes(StandardCharsets.UTF_8);

  @Test
  void disabledProductIsRejected() {
    final IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                FeatureFlagsRuntime.create(
                    AgentBridge.NONE, TestSettings.of("feature.flags.enabled", "false")));

    assertEquals("Feature Flags is disabled", error.getMessage());
  }

  @Test
  void remoteConfigurationRequiresTheJavaAgent() {
    final IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                FeatureFlagsRuntime.create(
                    AgentBridge.NONE,
                    TestSettings.of("feature.flags.configuration.source", "remote_config")));

    assertTrue(error.getMessage().contains("requires a compatible Datadog Java agent"));
  }

  @Test
  void agentlessWithoutApiKeyOrProxyDisablesEventDelivery() {
    final FeatureFlagsRuntime runtime =
        FeatureFlagsRuntime.create(AgentBridge.NONE, TestSettings.of());

    assertNull(runtime.evaluations());
  }

  @Test
  void evaluationCountsKillSwitchDisablesTheEvaluationPipeline() {
    final FeatureFlagsRuntime runtime =
        FeatureFlagsRuntime.create(
            new TestAgentBridge(null, (route, json) -> {}),
            TestSettings.of("flagging.evaluation.counts.enabled", "false"));

    assertNull(runtime.evaluations());
  }

  @Test
  void exposuresDatadogLoggingKillSwitchDisablesTheExposurePipelineButKeepsTheCache() {
    final FeatureFlagsRuntime runtime =
        FeatureFlagsRuntime.create(
            new TestAgentBridge(null, (route, json) -> {}),
            TestSettings.of("feature.flags.exposures.datadog.logging.enabled", "false"));

    assertFalse(runtime.isExposureDeliveryEnabled());
    assertNotNull(runtime.exposureCache());
    assertNotNull(runtime.evaluations(), "flag evaluation events are still sent");
  }

  @Test
  void agentlessWithoutApiKeyOrProxyKeepsTheExposureCache() {
    final FeatureFlagsRuntime runtime =
        FeatureFlagsRuntime.create(AgentBridge.NONE, TestSettings.of());

    assertFalse(runtime.isExposureDeliveryEnabled());
    assertNotNull(runtime.exposureCache());
  }

  @Test
  void eventProxyEnablesBothPipelines() {
    final FeatureFlagsRuntime runtime =
        FeatureFlagsRuntime.create(
            new TestAgentBridge(null, (route, json) -> {}), TestSettings.of());

    assertNotNull(runtime.evaluations());
    assertTrue(runtime.isExposureDeliveryEnabled());
  }

  @Test
  void remoteConfigurationDeliversParsedConfigurationsAndReplaysToLateListeners() {
    final TestConfigurationSource source = new TestConfigurationSource();
    final FeatureFlagsRuntime runtime =
        FeatureFlagsRuntime.create(
            new TestAgentBridge(source, null),
            TestSettings.of("feature.flags.configuration.source", "remote_config"));
    final List<ServerConfiguration> early = new CopyOnWriteArrayList<>();
    runtime.addConfigurationListener(early::add);
    runtime.start();
    try {
      source.listener.accept(EMPTY_UFC);
      source.listener.accept("{not json".getBytes(StandardCharsets.UTF_8));
      final List<ServerConfiguration> late = new CopyOnWriteArrayList<>();
      runtime.addConfigurationListener(late::add);

      assertEquals(1, early.size());
      assertEquals("Test", early.get(0).environment.name);
      assertEquals(1, late.size());
      assertSame(early.get(0), late.get(0));

      source.listener.accept(null);
      assertEquals(2, early.size());
      assertNull(early.get(1));
    } finally {
      runtime.stop();
    }
    assertTrue(source.closed);
  }

  @Test
  void removedListenersAreNotNotified() {
    final TestConfigurationSource source = new TestConfigurationSource();
    final FeatureFlagsRuntime runtime =
        FeatureFlagsRuntime.create(
            new TestAgentBridge(source, null),
            TestSettings.of("feature.flags.configuration.source", "remote_config"));
    @SuppressWarnings("unchecked")
    final Consumer<ServerConfiguration> listener = mock(Consumer.class);
    runtime.addConfigurationListener(listener);
    runtime.removeConfigurationListener(listener);
    runtime.start();
    try {
      source.listener.accept(EMPTY_UFC);
    } finally {
      runtime.stop();
    }

    verify(listener, org.mockito.Mockito.never()).accept(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void failingListenerDoesNotStarveOtherListeners() {
    final TestConfigurationSource source = new TestConfigurationSource();
    final FeatureFlagsRuntime runtime =
        FeatureFlagsRuntime.create(
            new TestAgentBridge(source, null),
            TestSettings.of("feature.flags.configuration.source", "remote_config"));
    final List<ServerConfiguration> received = new CopyOnWriteArrayList<>();
    runtime.addConfigurationListener(
        configuration -> {
          throw new IllegalStateException("listener failure");
        });
    runtime.addConfigurationListener(received::add);
    runtime.start();
    try {
      source.listener.accept(EMPTY_UFC);
    } finally {
      runtime.stop();
    }

    assertEquals(1, received.size());
  }

  @Test
  void sharedRuntimeIsReferenceCounted() {
    final TestConfigurationSource source = new TestConfigurationSource();
    final AgentBridge bridge = new TestAgentBridge(source, null);
    final FeatureFlagsRuntime first =
        FeatureFlagsRuntime.acquire(
            bridge, TestSettings.of("feature.flags.configuration.source", "remote_config"));
    final FeatureFlagsRuntime second = FeatureFlagsRuntime.acquire(bridge, TestSettings.of());
    try {
      assertSame(first, second);
      FeatureFlagsRuntime.release();
      assertTrue(!source.closed);
    } finally {
      FeatureFlagsRuntime.release();
    }
    assertTrue(source.closed);
    // Extra releases are ignored.
    FeatureFlagsRuntime.release();
  }

  @Test
  void lastReleaseFlushesPendingExposures() {
    final TestConfigurationSource source = new TestConfigurationSource();
    final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
    final AgentBridge bridge =
        new TestAgentBridge(
            source,
            (route, json) -> {
              assertEquals("exposures", route);
              requests.add(JsonReading.readObject(json));
            });
    final FeatureFlagsRuntime first =
        FeatureFlagsRuntime.acquire(
            bridge,
            TestSettings.of(
                "feature.flags.configuration.source", "remote_config",
                "flagging.evaluation.counts.enabled", "false"));
    try {
      final FeatureFlagsRuntime second = FeatureFlagsRuntime.acquire(bridge, TestSettings.of());
      try {
        assertSame(first, second);
      } finally {
        FeatureFlagsRuntime.release();
      }
      assertFalse(source.closed);
      first.recordExposure(
          new com.datadog.openfeature.internal.exposure.ExposureEvent(
              1L,
              new com.datadog.openfeature.internal.exposure.Allocation("a"),
              new com.datadog.openfeature.internal.exposure.Flag("f"),
              new com.datadog.openfeature.internal.exposure.Variant("v"),
              new com.datadog.openfeature.internal.exposure.Subject("s", emptyMap()),
              null));
    } finally {
      FeatureFlagsRuntime.release();
    }

    assertTrue(source.closed);
    assertEquals(1, requests.size());
    assertEquals(1, ((List<?>) requests.get(0).get("exposures")).size());
  }

  @Test
  void agentShutdownFlushesPendingExposuresOnce() {
    final TestConfigurationSource source = new TestConfigurationSource();
    final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
    final TestAgentBridge bridge =
        new TestAgentBridge(source, (route, json) -> requests.add(JsonReading.readObject(json)))
            .withAgentShutdown();
    final FeatureFlagsRuntime runtime =
        FeatureFlagsRuntime.acquire(
            bridge,
            TestSettings.of(
                "feature.flags.configuration.source", "remote_config",
                "flagging.evaluation.counts.enabled", "false"));
    try {
      assertNotNull(bridge.shutdownFlush, "the runtime registers its flush with the agent");
      runtime.recordExposure(exposure());

      bridge.shutdownFlush.run();

      assertTrue(source.closed);
      assertTrue(bridge.shutdownUnregistered);
      assertEquals(1, requests.size());
    } finally {
      FeatureFlagsRuntime.release();
    }
    assertEquals(1, requests.size(), "a release after the agent shutdown does not flush again");
  }

  @Test
  void releaseUnregistersTheAgentShutdownFlush() {
    final TestAgentBridge bridge =
        new TestAgentBridge(new TestConfigurationSource(), null).withAgentShutdown();
    FeatureFlagsRuntime.acquire(
        bridge, TestSettings.of("feature.flags.configuration.source", "remote_config"));

    FeatureFlagsRuntime.release();

    assertTrue(bridge.shutdownUnregistered);
  }

  @Test
  void stopFlushesBothPipelinesWithinOneShutdownBudget() throws Exception {
    final CountDownLatch exposuresPosted = new CountDownLatch(1);
    final CountDownLatch evaluationsPosted = new CountDownLatch(1);
    final CountDownLatch releasePosts = new CountDownLatch(1);
    final EventTransport stalledTransport =
        (route, json) -> {
          ("exposures".equals(route) ? exposuresPosted : evaluationsPosted).countDown();
          try {
            releasePosts.await(10, SECONDS);
          } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        };
    final FeatureFlagsRuntime runtime =
        FeatureFlagsRuntime.create(
            new TestAgentBridge(new TestConfigurationSource(), stalledTransport),
            TestSettings.of("feature.flags.configuration.source", "remote_config"));
    runtime.start();
    try {
      runtime.recordExposure(exposure());
      runtime.evaluations().enqueue(new FlagEvalEvent("f", "v", "a", "s", 1L, emptyMap()));

      final long started = System.nanoTime();
      runtime.stop();
      final long elapsedMillis = NANOSECONDS.toMillis(System.nanoTime() - started);

      assertEquals(0, evaluationsPosted.getCount(), "the evaluations final flush started");
      assertEquals(0, exposuresPosted.getCount(), "the exposures final flush started");
      assertTrue(elapsedMillis < 1300, "Both pipeline waits took " + elapsedMillis + " ms");
    } finally {
      releasePosts.countDown();
    }
  }

  @Test
  void failingAgentShutdownSubscriptionFallsBackToTheJvmShutdownHook() {
    final TestConfigurationSource source = new TestConfigurationSource();
    final TestAgentBridge bridge =
        new TestAgentBridge(source, null)
            .withFailingAgentShutdown(new IllegalStateException("Shutdown in progress"));

    final FeatureFlagsRuntime runtime =
        FeatureFlagsRuntime.acquire(
            bridge, TestSettings.of("feature.flags.configuration.source", "remote_config"));
    assertNotNull(runtime);
    FeatureFlagsRuntime.release();

    assertTrue(source.closed, "the started runtime is stopped on release");
  }

  @Test
  void failedStartDoesNotCacheTheSharedRuntime() {
    final ConfigurationSource failing =
        listener -> {
          throw new IllegalStateException("subscription failed");
        };
    assertThrows(
        IllegalStateException.class,
        () ->
            FeatureFlagsRuntime.acquire(
                new TestAgentBridge(failing, null),
                TestSettings.of("feature.flags.configuration.source", "remote_config")));

    final TestConfigurationSource source = new TestConfigurationSource();
    final FeatureFlagsRuntime runtime =
        FeatureFlagsRuntime.acquire(
            new TestAgentBridge(source, null),
            TestSettings.of("feature.flags.configuration.source", "remote_config"));
    try {
      assertNotNull(runtime);
      assertNotNull(source.listener, "a later acquisition starts a new runtime");
    } finally {
      FeatureFlagsRuntime.release();
    }
  }

  @Test
  void recordsExposuresOnlyWhenDeliveryIsEnabled() {
    final FeatureFlagsRuntime runtime =
        FeatureFlagsRuntime.create(AgentBridge.NONE, TestSettings.of());
    // Must not throw without an exposure pipeline.
    runtime.recordExposure(
        new com.datadog.openfeature.internal.exposure.ExposureEvent(
            1L,
            new com.datadog.openfeature.internal.exposure.Allocation("a"),
            new com.datadog.openfeature.internal.exposure.Flag("f"),
            new com.datadog.openfeature.internal.exposure.Variant("v"),
            new com.datadog.openfeature.internal.exposure.Subject("s", emptyMap()),
            null));
  }

  private static com.datadog.openfeature.internal.exposure.ExposureEvent exposure() {
    return new com.datadog.openfeature.internal.exposure.ExposureEvent(
        1L,
        new com.datadog.openfeature.internal.exposure.Allocation("a"),
        new com.datadog.openfeature.internal.exposure.Flag("f"),
        new com.datadog.openfeature.internal.exposure.Variant("v"),
        new com.datadog.openfeature.internal.exposure.Subject("s", emptyMap()),
        null);
  }

  private static final class TestConfigurationSource implements ConfigurationSource {
    volatile Consumer<byte[]> listener;
    volatile boolean closed;

    @Override
    public AutoCloseable subscribe(final Consumer<byte[]> listener) {
      this.listener = listener;
      return () -> this.closed = true;
    }
  }

  private static final class TestAgentBridge implements AgentBridge {
    private final ConfigurationSource remoteConfiguration;
    private final EventTransport eventProxy;
    private boolean agentShutdown;
    private RuntimeException shutdownFailure;
    volatile Runnable shutdownFlush;
    volatile boolean shutdownUnregistered;

    TestAgentBridge(
        final ConfigurationSource remoteConfiguration, final EventTransport eventProxy) {
      this.remoteConfiguration = remoteConfiguration;
      this.eventProxy = eventProxy;
    }

    @Override
    public String setting(final String key) {
      return null;
    }

    @Override
    public ConfigurationSource remoteConfiguration() {
      return this.remoteConfiguration;
    }

    @Override
    public EventTransport eventProxy() {
      return this.eventProxy;
    }

    @Override
    public HealthMetrics healthMetrics() {
      return HealthMetrics.NOOP;
    }

    @Override
    public SpanEnricher spanEnricher() {
      return SpanEnricher.NOOP;
    }

    @Override
    public ShutdownSource shutdown() {
      if (!this.agentShutdown) {
        return null;
      }
      return listener -> {
        if (this.shutdownFailure != null) {
          throw this.shutdownFailure;
        }
        this.shutdownFlush = listener;
        return () -> this.shutdownUnregistered = true;
      };
    }

    TestAgentBridge withAgentShutdown() {
      this.agentShutdown = true;
      return this;
    }

    TestAgentBridge withFailingAgentShutdown(final RuntimeException failure) {
      this.shutdownFailure = failure;
      return withAgentShutdown();
    }
  }
}
