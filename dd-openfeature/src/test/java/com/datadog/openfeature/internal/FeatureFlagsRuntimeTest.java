package com.datadog.openfeature.internal;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.datadog.openfeature.internal.FeatureFlagsRuntime.RemoteConfigurationUnavailableException;
import com.datadog.openfeature.internal.bridge.v1.AgentBridge;
import com.datadog.openfeature.internal.bridge.v1.ConfigurationSource;
import com.datadog.openfeature.internal.bridge.v1.EventTransport;
import com.datadog.openfeature.internal.bridge.v1.HealthMetrics;
import com.datadog.openfeature.internal.bridge.v1.ShutdownSource;
import com.datadog.openfeature.internal.bridge.v1.SpanEnricher;
import com.datadog.openfeature.internal.config.Settings;
import com.datadog.openfeature.internal.config.TestSettings;
import com.datadog.openfeature.internal.delivery.FlagSelectionRequest;
import com.datadog.openfeature.internal.delivery.FlagSelectionTestSupport;
import com.datadog.openfeature.internal.delivery.FlagSelectionTestSupport.RecordingHealthMetrics;
import com.datadog.openfeature.internal.flagevaluation.FlagEvalEvent;
import com.datadog.openfeature.internal.ufc.ServerConfiguration;
import com.datadog.openfeature.testlog.CapturingServiceProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

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
      assertThrows(
          IllegalArgumentException.class,
          () -> source.listener.accept("{not json".getBytes(StandardCharsets.UTF_8)));
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

  @AfterEach
  void resetCapturedLogs() {
    CapturingServiceProvider.reset();
  }

  @Test
  void stoppingDoesNotDependOnTheLoggingBackend() {
    CapturingServiceProvider.enable("com.datadog.openfeature", Level.DEBUG);
    CapturingServiceProvider.failOn("Feature Flags runtime stopped");
    final TestConfigurationSource source = new TestConfigurationSource();
    final FeatureFlagsRuntime runtime =
        FeatureFlagsRuntime.create(
            new TestAgentBridge(source, null),
            TestSettings.of("feature.flags.configuration.source", "remote_config"));
    runtime.start();

    assertThrows(IllegalStateException.class, runtime::stop);

    assertTrue(source.closed);
  }

  @Test
  void appliesConfigurationsWhenTheirLogFails() {
    CapturingServiceProvider.enable("com.datadog.openfeature", Level.DEBUG);
    CapturingServiceProvider.failOn("Feature Flags configuration");
    final TestConfigurationSource source = new TestConfigurationSource();
    final FeatureFlagsRuntime runtime =
        FeatureFlagsRuntime.create(
            new TestAgentBridge(source, null),
            TestSettings.of("feature.flags.configuration.source", "remote_config"));
    final List<ServerConfiguration> received = new CopyOnWriteArrayList<>();
    runtime.addConfigurationListener(received::add);
    runtime.start();
    try {
      assertThrows(IllegalStateException.class, () -> source.listener.accept(EMPTY_UFC));
      assertEquals(1, received.size(), "The listener must not wait for the log");
      assertThrows(IllegalStateException.class, () -> source.listener.accept(null));
      assertEquals(2, received.size(), "The removal must not wait for the log");
      assertNull(received.get(1));

      final List<ServerConfiguration> late = new CopyOnWriteArrayList<>();
      runtime.addConfigurationListener(late::add);
      assertTrue(late.isEmpty(), "The removed configuration is still the current one");
    } finally {
      CapturingServiceProvider.reset();
      runtime.stop();
    }
  }

  @Test
  void reportsAppliedAndRemovedConfigurations() {
    CapturingServiceProvider.enable("com.datadog.openfeature", Level.DEBUG);
    final TestConfigurationSource source = new TestConfigurationSource();
    final FeatureFlagsRuntime runtime =
        FeatureFlagsRuntime.create(
            new TestAgentBridge(source, null),
            TestSettings.of("feature.flags.configuration.source", "remote_config"));
    runtime.start();
    try {
      source.listener.accept(EMPTY_UFC);
      source.listener.accept(null);
    } finally {
      runtime.stop();
    }

    final List<String> messages =
        CapturingServiceProvider.events().stream().map(e -> e.message).collect(Collectors.toList());
    assertTrue(
        messages.contains(
            "Feature Flags configuration applied: flags=0, invalidFlags=0, configurationEnv=Test, createdAt=null"),
        messages.toString());
    assertTrue(messages.contains("Feature Flags configuration removed"), messages.toString());
    assertTrue(messages.contains("Feature Flags runtime stopped"), messages.toString());
  }

  @Test
  void concurrentRemovalIsDeliveredAfterTheInitialReplay() throws Exception {
    final TestConfigurationSource source = new TestConfigurationSource();
    final FeatureFlagsRuntime runtime =
        FeatureFlagsRuntime.create(
            new TestAgentBridge(source, null),
            TestSettings.of("feature.flags.configuration.source", "remote_config"));
    runtime.start();
    source.listener.accept(EMPTY_UFC);
    final List<ServerConfiguration> received = new CopyOnWriteArrayList<>();
    final CountDownLatch replayStarted = new CountDownLatch(1);
    final CountDownLatch finishReplay = new CountDownLatch(1);
    final CountDownLatch removalStarted = new CountDownLatch(1);
    final ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      final Future<?> registration =
          executor.submit(() ->
              runtime.addConfigurationListener(configuration -> {
                if (configuration != null) {
                  replayStarted.countDown();
                  try {
                    assertTrue(finishReplay.await(5, SECONDS));
                  } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                  }
                }
                received.add(configuration);
              }));
      assertTrue(replayStarted.await(5, SECONDS));
      final Future<?> removal =
          executor.submit(() -> {
            removalStarted.countDown();
            source.listener.accept(null);
          });
      assertTrue(removalStarted.await(5, SECONDS));
      assertThrows(TimeoutException.class, () -> removal.get(100, MILLISECONDS));

      finishReplay.countDown();
      registration.get(5, SECONDS);
      removal.get(5, SECONDS);

      assertEquals(2, received.size());
      assertEquals("Test", received.get(0).environment.name);
      assertNull(received.get(1));
    } finally {
      finishReplay.countDown();
      executor.shutdownNow();
      runtime.stop();
    }
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
    runtime.addConfigurationListener(configuration -> {
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

  @Test
  void firstSelectionWinsAndALaterDifferentOneWarnsOnce() throws Exception {
    try (FlagSelectionScope scope = new FlagSelectionScope()) {
      final FeatureFlagsRuntime first = scope.acquire(TEAM_CHECKOUT);
      assertSame(first, scope.acquire(FlagSelectionRequest.of(singletonList("team:search"), null)));
      assertSame(first, scope.acquire(FlagSelectionRequest.NONE));
      assertSame(first, scope.acquire(FlagSelectionRequest.of(asList(" team:checkout"), null)));

      assertEquals(singletonList(SECOND_SELECTION_WARNING), scope.messages());
      assertEquals(1, scope.cdn.requests().size());
      assertEquals(TEAM_CHECKOUT_QUERY, scope.cdn.requests().get(0).uri.getRawQuery());
    }
  }

  @Test
  void providerWithoutSelectionClaimsAllTheFlags() throws Exception {
    try (FlagSelectionScope scope = new FlagSelectionScope()) {
      final FeatureFlagsRuntime first = scope.acquire(FlagSelectionRequest.NONE);
      assertSame(first, scope.acquire(FlagSelectionRequest.of(asList(" ", ""), null)));
      assertSame(first, scope.acquire(TEAM_CHECKOUT));

      assertEquals(singletonList(SECOND_SELECTION_WARNING), scope.messages());
      assertNull(scope.cdn.requests().get(0).uri.getRawQuery());
    }
  }

  @Test
  void lastReleaseFreesTheClaim() throws Exception {
    try (FlagSelectionScope scope = new FlagSelectionScope()) {
      final FeatureFlagsRuntime first = scope.acquire(FlagSelectionRequest.NONE);
      scope.releaseAll();
      final FeatureFlagsRuntime second = scope.acquire(TEAM_CHECKOUT);

      assertNotSame(first, second, "the last release stops the runtime");
      assertTrue(scope.messages().isEmpty(), scope.messages().toString());
      assertNull(scope.cdn.requests().get(0).uri.getRawQuery());
      assertEquals(TEAM_CHECKOUT_QUERY, scope.cdn.requests().get(1).uri.getRawQuery());
    }
  }

  @Test
  void droppedSelectionIsReportedAtEachProviderInitAndCountedOnce() throws Exception {
    final FlagSelectionRequest tooMany = FlagSelectionRequest.of(values(32), null);
    try (FlagSelectionScope scope = new FlagSelectionScope()) {
      scope.acquire(tooMany);
      scope.acquire(FlagSelectionRequest.of(values(33), null));
      scope.acquire(FlagSelectionRequest.NONE);

      final String notSent =
          "ERROR flagSelection was not sent (too_many_values: each list accepts at most 31"
              + " values). Loading the full flag configuration.";
      assertEquals(asList(notSent, notSent, SECOND_SELECTION_WARNING), scope.messages());
      assertEquals(singletonList("flag_selection.dropped|1|too_many_values"), scope.metrics.counts);
      assertNull(scope.cdn.requests().get(0).uri.getRawQuery());
    }
  }

  @Test
  void overlongUrlIsReportedAsTooManyValues() throws Exception {
    final char[] padding = new char[8150];
    Arrays.fill(padding, 'x');
    try (FlagSelectionScope scope = new FlagSelectionScope("/ufc?pad=" + new String(padding))) {
      scope.acquire(TEAM_CHECKOUT);
      scope.acquire(TEAM_CHECKOUT);

      final String notSent =
          "ERROR flagSelection was not sent (too_many_values: the request URL is at most 8192"
              + " bytes). Loading the full flag configuration.";
      assertEquals(asList(notSent, notSent), scope.messages());
      assertEquals(singletonList("flag_selection.dropped|1|too_many_values"), scope.metrics.counts);
      assertEquals("pad=" + new String(padding), scope.cdn.requests().get(0).uri.getRawQuery());
    }
  }

  @Test
  void remoteConfigurationWarnsOnceWhenASelectionIsSet() {
    FlagSelectionTestSupport.resetReported();
    CapturingServiceProvider.enable("com.datadog.openfeature", Level.WARN);
    final RecordingHealthMetrics metrics = new RecordingHealthMetrics();
    final TestConfigurationSource source = new TestConfigurationSource();
    final AgentBridge bridge = new TestAgentBridge(source, null).withHealthMetrics(metrics);
    final Settings remoteConfig =
        TestSettings.of("feature.flags.configuration.source", "remote_config");
    try {
      FeatureFlagsRuntime.acquire(bridge, remoteConfig, FlagSelectionRequest.NONE);
      FeatureFlagsRuntime.acquire(bridge, remoteConfig, FlagSelectionRequest.of(asList(" "), null));
      FeatureFlagsRuntime.release();
      FeatureFlagsRuntime.release();
      assertTrue(flagSelectionMessages().isEmpty(), "no selection, no warning");

      final FlagSelectionRequest dropped = FlagSelectionRequest.of(values(32), null);
      FeatureFlagsRuntime.acquire(bridge, remoteConfig, dropped);
      FeatureFlagsRuntime.acquire(bridge, remoteConfig, dropped);
      FeatureFlagsRuntime.release();
      FeatureFlagsRuntime.release();
      FeatureFlagsRuntime.acquire(bridge, remoteConfig, TEAM_CHECKOUT);
      FeatureFlagsRuntime.release();

      assertEquals(singletonList(REMOTE_CONFIG_WARNING), flagSelectionMessages());
      assertTrue(metrics.counts.isEmpty());
    } finally {
      CapturingServiceProvider.reset();
      FlagSelectionTestSupport.resetReported();
    }
  }

  @Test
  void remoteConfigurationWarnsAJoiningProviderWithASelection() {
    FlagSelectionTestSupport.resetReported();
    CapturingServiceProvider.enable("com.datadog.openfeature", Level.WARN);
    final AgentBridge bridge = new TestAgentBridge(new TestConfigurationSource(), null);
    final Settings legacyRemoteConfig =
        TestSettings.of("experimental.flagging.provider.enabled", "true");
    try {
      FeatureFlagsRuntime.acquire(bridge, legacyRemoteConfig, FlagSelectionRequest.NONE);
      FeatureFlagsRuntime.acquire(bridge, legacyRemoteConfig, TEAM_CHECKOUT);
      FeatureFlagsRuntime.acquire(bridge, legacyRemoteConfig, TEAM_CHECKOUT);

      assertEquals(
          asList(REMOTE_CONFIG_WARNING, SECOND_SELECTION_WARNING), flagSelectionMessages());
    } finally {
      FeatureFlagsRuntime.release();
      FeatureFlagsRuntime.release();
      FeatureFlagsRuntime.release();
      CapturingServiceProvider.reset();
      FlagSelectionTestSupport.resetReported();
    }
  }

  @Test
  void remoteConfigurationWarningPrecedesTheConnectorCheck() {
    FlagSelectionTestSupport.resetReported();
    CapturingServiceProvider.enable("com.datadog.openfeature", Level.WARN);
    try {
      assertThrows(
          RemoteConfigurationUnavailableException.class,
          () ->
              FeatureFlagsRuntime.create(
                  AgentBridge.NONE,
                  TestSettings.of("feature.flags.configuration.source", "remote_config"),
                  TEAM_CHECKOUT));

      assertEquals(singletonList(REMOTE_CONFIG_WARNING), flagSelectionMessages());
    } finally {
      CapturingServiceProvider.reset();
      FlagSelectionTestSupport.resetReported();
    }
  }

  @Test
  void failingSelectionDiagnosticsDoNotFailTheAcquisition() throws Exception {
    try (FlagSelectionScope scope = new FlagSelectionScope()) {
      CapturingServiceProvider.failOn("flagSelection");
      final FeatureFlagsRuntime first =
          assertDoesNotThrow(() -> scope.acquire(FlagSelectionRequest.of(values(32), null)));
      assertSame(first, assertDoesNotThrow(() -> scope.acquire(TEAM_CHECKOUT)));

      assertEquals(2, scope.messages().size());
    }
  }

  private static final FlagSelectionRequest TEAM_CHECKOUT =
      FlagSelectionRequest.of(singletonList("team:checkout"), null);
  private static final String TEAM_CHECKOUT_QUERY = "selected_flag_tags=team%3Acheckout";
  private static final String SECOND_SELECTION_WARNING =
      "WARN flagSelection differs from the flag selection already in use in this process. Using"
          + " the first selection.";
  private static final String REMOTE_CONFIG_WARNING =
      "WARN flagSelection is not supported when flags are delivered through Remote Config. Loading"
          + " the full flag configuration.";

  private static List<String> values(final int count) {
    final List<String> values = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      values.add("t" + i);
    }
    return values;
  }

  private static List<String> flagSelectionMessages() {
    return CapturingServiceProvider.events().stream()
        .filter(e -> e.message.startsWith("flagSelection"))
        .map(e -> e.level + " " + e.message)
        .collect(Collectors.toList());
  }

  /** Acquires agentless runtimes polling a local CDN, and releases them all when closed. */
  private static final class FlagSelectionScope implements AutoCloseable {
    final LocalHttpServer cdn = new LocalHttpServer();
    final RecordingHealthMetrics metrics = new RecordingHealthMetrics();
    private final AgentBridge bridge = new TestAgentBridge(null, null).withHealthMetrics(metrics);
    private final Settings settings;
    private int acquired;

    FlagSelectionScope() throws IOException {
      this("/ufc");
    }

    FlagSelectionScope(final String path) throws IOException {
      this.cdn.otherwise(200, CDN_CONFIG);
      this.settings =
          TestSettings.of(
              "feature.flags.configuration.source.agentless.base.url",
              this.cdn.uri(path).toString());
      FlagSelectionTestSupport.resetReported();
      CapturingServiceProvider.enable("com.datadog.openfeature", Level.WARN);
    }

    FeatureFlagsRuntime acquire(final FlagSelectionRequest selection) {
      final FeatureFlagsRuntime runtime =
          FeatureFlagsRuntime.acquire(this.bridge, this.settings, selection);
      this.acquired++;
      return runtime;
    }

    void releaseAll() {
      while (this.acquired > 0) {
        this.acquired--;
        FeatureFlagsRuntime.release();
      }
    }

    List<String> messages() {
      return flagSelectionMessages();
    }

    @Override
    public void close() {
      try {
        CapturingServiceProvider.reset();
        releaseAll();
      } finally {
        this.cdn.close();
        FlagSelectionTestSupport.resetReported();
      }
    }
  }

  private static final String CDN_CONFIG =
      "{\"data\":{\"type\":\"universal-flag-configuration\",\"attributes\":"
          + "{\"environment\":{\"name\":\"Test\"},\"flags\":{}}}}";

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
    private HealthMetrics healthMetrics = HealthMetrics.NOOP;
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
      return this.healthMetrics;
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

    TestAgentBridge withHealthMetrics(final HealthMetrics healthMetrics) {
      this.healthMetrics = healthMetrics;
      return this;
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
