package com.datadog.profiling.agent;

import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datadog.profiling.controller.Controller;
import com.datadog.profiling.controller.OngoingRecording;
import com.datadog.profiling.controller.ProfilingSystem;
import com.datadog.profiling.controller.UnsupportedEnvironmentException;
import com.datadog.profiling.ddprof.DatadogProfiler;
import com.datadog.profiling.remoteconfig.RemoteConfiguration;
import com.datadog.profiling.remoteconfig.RemoteConfigurationStack;
import datadog.trace.api.profiling.ProfilingSnapshot;
import datadog.trace.api.profiling.RecordingDataListener;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProfilingRemoteConfigHandlerTest {
  // the config name that the cpuTime feature sets, which tells the tests whether anything is on
  private static final String ENABLED_KEY = "profiling.ddprof.cpu.enabled";
  private static final String WALL_KEY = "profiling.ddprof.wall.enabled";
  private static final String ALL_FEATURES_DISABLED =
      "\"cpuTime\":false,\"wallTime\":false,\"heapObjects\":false," + "\"allocations\":false";

  private final ConfigProvider localConfig = ConfigProvider.getInstance();
  private final Controller controller = mock(Controller.class);
  private final OngoingRecording recording = mock(OngoingRecording.class);
  // the remote values each created provider was built from
  private final Map<ConfigProvider, Map<String, String>> remoteValues = new IdentityHashMap<>();
  private final List<ConfigProvider> createdConfigs = new ArrayList<>();

  private ProfilingSystem profilingSystem;

  @BeforeEach
  void setUp() throws Exception {
    when(controller.createRecording(any(), any())).thenReturn(recording);
    // long delays: only immediate starts record, and no snapshot runs during the test
    profilingSystem =
        new ProfilingSystem(
            localConfig,
            context -> controller,
            mock(RecordingDataListener.class),
            // no startup delay, so that a start is right away where that is safe
            Duration.ZERO,
            Duration.ofMillis(1),
            Duration.ofHours(1),
            false);
  }

  @AfterEach
  void tearDown() {
    profilingSystem.stop(false);
  }

  private ProfilingRemoteConfigHandler handler() {
    return handler(profilingSystem);
  }

  /** A handler that decides from the values, like in production, whether anything is enabled. */
  private ProfilingRemoteConfigHandler handlerWithRealFeatureCheck() {
    return new ProfilingRemoteConfigHandler(
        profilingSystem,
        localConfig,
        ConfigProvider::withRemoteOverride,
        DatadogProfiler::isAnyProfilingModeEnabled,
        true);
  }

  private ProfilingRemoteConfigHandler handler(ProfilingSystem system) {
    return handler(system, true);
  }

  /**
   * A handler that records the values it builds the configurations from, and for which only cpuTime
   * enables anything.
   *
   * @param startImmediately whether starting right away is safe
   */
  private ProfilingRemoteConfigHandler handler(ProfilingSystem system, boolean startImmediately) {
    return new ProfilingRemoteConfigHandler(
        system,
        localConfig,
        values -> {
          ConfigProvider config = ConfigProvider.withRemoteOverride(values);
          remoteValues.put(config, values);
          createdConfigs.add(config);
          return config;
        },
        config -> "true".equals(remoteValues.getOrDefault(config, emptyMap()).get(ENABLED_KEY)),
        startImmediately);
  }

  @Test
  void idleStartsWhenRemoteConfigEnablesProfiling() throws Exception {
    handler().onConfigsChanged(enabledConfig());

    assertTrue(profilingSystem.isRunning());
    assertTrue(profilingSystem.isStarted(), "started immediately, without the startup delay");
    assertEquals(1, createdConfigs.size());
    verify(controller).reconfigure(createdConfigs.get(0));
    verify(controller).createRecording(any(), any());
  }

  @Test
  void idleStaysIdleWhenRemoteConfigDisablesEverything() throws Exception {
    handler().onConfigsChanged(disabledConfig());

    assertFalse(profilingSystem.isRunning());
    verify(controller, never()).reconfigure(any());
    verify(controller, never()).createRecording(any(), any());
  }

  @Test
  void runningRestartsWithNewConfiguration() throws Exception {
    ProfilingRemoteConfigHandler handler = handler();
    handler.onConfigsChanged(enabledConfig());

    handler.onConfigsChanged(otherEnabledConfig());

    assertTrue(profilingSystem.isRunning());
    assertEquals(2, createdConfigs.size());
    verify(recording).stop();
    verify(controller).reconfigure(createdConfigs.get(1));
    verify(controller, times(2)).createRecording(any(), any());
    assertEquals("true", remoteValues.get(createdConfigs.get(1)).get(WALL_KEY));
  }

  @Test
  void runningStopsWhenRemoteConfigDisablesEverything() throws Exception {
    ProfilingRemoteConfigHandler handler = handler();
    handler.onConfigsChanged(enabledConfig());

    handler.onConfigsChanged(disabledConfig());

    assertFalse(profilingSystem.isRunning());
    verify(recording).stop();
    verify(controller, times(1)).reconfigure(any());
    verify(controller, times(1)).createRecording(any(), any());
  }

  @Test
  void runningStopsWhenAHigherPriorityConfigDisablesWhatTheOthersEnable() throws Exception {
    ProfilingRemoteConfigHandler handler = handler();
    handler.onConfigsChanged(enabledConfig());

    // the merged configuration has the only feature that was enabled disabled
    RemoteConfigurationStack stack = new RemoteConfigurationStack();
    stack.push("general", 0, config("\"cpuTime\":true"));
    stack.push("specific", 3001, config("\"cpuTime\":false"));
    handler.onConfigsChanged(stack);

    assertFalse(profilingSystem.isRunning());
    verify(recording).stop();
    verify(controller, times(1)).createRecording(any(), any());
  }

  @Test
  void highestPriorityConfigDecidesWhateverTheConfigId() throws Exception {
    // by config id, "z" would overwrite "a": it has the lower priority, so it must not
    RemoteConfigurationStack stack = new RemoteConfigurationStack();
    stack.push("a", 3001, config("\"cpuTime\":true"));
    stack.push("z", 0, config("\"cpuTime\":false"));

    handler().onConfigsChanged(stack);

    assertTrue(profilingSystem.isRunning());
    assertEquals("true", remoteValues.get(createdConfigs.get(0)).get(ENABLED_KEY));
  }

  @Test
  void configurationsAreMergedFeatureByFeature() throws Exception {
    RemoteConfigurationStack stack = new RemoteConfigurationStack();
    stack.push("general", 0, config("\"cpuTime\":true,\"wallTime\":false"));
    stack.push("specific", 2001, config("\"wallTime\":true"));

    handler().onConfigsChanged(stack);

    Map<String, String> applied = remoteValues.get(createdConfigs.get(0));
    assertEquals("true", applied.get(ENABLED_KEY), "from the general configuration");
    assertEquals("true", applied.get(WALL_KEY), "from the specific configuration");
  }

  // The tests below use the real check of what the merged values enable, which tells that all the
  // profiling features are disabled from the values only.

  @Test
  void runningStopsWhenTheValuesDisableEveryFeature() throws Exception {
    ProfilingRemoteConfigHandler handler = handlerWithRealFeatureCheck();
    handler.onConfigsChanged(stackOf("\"cpuTime\":true"));
    assertTrue(profilingSystem.isRunning());

    handler.onConfigsChanged(stackOf(ALL_FEATURES_DISABLED));

    assertFalse(profilingSystem.isRunning());
    verify(recording).stop();
    verify(controller, times(1)).createRecording(any(), any());
  }

  @Test
  void idleStaysIdleWhenTheValuesDisableEveryFeature() throws Exception {
    handlerWithRealFeatureCheck().onConfigsChanged(stackOf(ALL_FEATURES_DISABLED));

    assertFalse(profilingSystem.isRunning());
    verify(controller, never()).createRecording(any(), any());
  }

  @Test
  void disablingOnlySomeFeaturesDoesNotStopTheProfiler() throws Exception {
    ProfilingRemoteConfigHandler handler = handlerWithRealFeatureCheck();
    handler.onConfigsChanged(stackOf("\"cpuTime\":true,\"wallTime\":true"));

    // wall time is disabled, but cpu time is still enabled
    handler.onConfigsChanged(stackOf("\"cpuTime\":true,\"wallTime\":false"));

    assertTrue(profilingSystem.isRunning());
  }

  @Test
  void higherPriorityConfigEnablingAFeatureStartsTheProfilerAgain() throws Exception {
    RemoteConfigurationStack stack = new RemoteConfigurationStack();
    stack.push("general", 0, config(ALL_FEATURES_DISABLED));
    stack.push("specific", 3001, config("\"wallTime\":true"));

    handlerWithRealFeatureCheck().onConfigsChanged(stack);

    // only the wall time is enabled in the merged configuration
    assertTrue(profilingSystem.isRunning());
    verify(controller).createRecording(any(), any());
  }

  @Test
  void removingRemoteConfigRevertsToIdleWhenArmed() throws Exception {
    ProfilingRemoteConfigHandler handler = handler();
    handler.onConfigsChanged(enabledConfig());

    handler.onConfigsChanged(new RemoteConfigurationStack());

    assertFalse(profilingSystem.isRunning());
    verify(recording).stop();
  }

  @Test
  void configsThatSetNoFeatureApplyTheLocalConfiguration() throws Exception {
    ProfilingRemoteConfigHandler handler = handler();
    handler.onConfigsChanged(enabledConfig());

    // there are configs, but none sets a feature, so it is as if there were none
    RemoteConfigurationStack stack = new RemoteConfigurationStack();
    stack.push("config-1", 0, RemoteConfiguration.EMPTY);
    handler.onConfigsChanged(stack);

    assertFalse(profilingSystem.isRunning());
    verify(recording).stop();
  }

  @Test
  void unchangedRemoteConfigIsIgnored() throws Exception {
    ProfilingRemoteConfigHandler handler = handler();
    handler.onConfigsChanged(enabledConfig());

    handler.onConfigsChanged(enabledConfig());

    assertTrue(profilingSystem.isRunning());
    assertEquals(1, createdConfigs.size());
    verify(recording, never()).stop();
    verify(controller, times(1)).createRecording(any(), any());
  }

  @Test
  void changeOfPriorityThatKeepsTheMergedConfigurationIsIgnored() throws Exception {
    ProfilingRemoteConfigHandler handler = handler();
    RemoteConfigurationStack first = new RemoteConfigurationStack();
    first.push("a", 0, config("\"cpuTime\":true"));
    handler.onConfigsChanged(first);

    RemoteConfigurationStack second = new RemoteConfigurationStack();
    second.push("a", 3001, config("\"cpuTime\":true"));
    handler.onConfigsChanged(second);

    assertEquals(1, createdConfigs.size());
    verify(recording, never()).stop();
  }

  @Test
  void noRemoteConfigInitiallyIsIgnored() throws Exception {
    handler().onConfigsChanged(new RemoteConfigurationStack());

    assertFalse(profilingSystem.isRunning());
    verify(controller, never()).reconfigure(any());
  }

  @Test
  void closedHandlerIgnoresRemoteConfig() throws Exception {
    ProfilingRemoteConfigHandler handler = handler();
    handler.close();

    handler.onConfigsChanged(enabledConfig());

    assertFalse(profilingSystem.isRunning());
    verify(controller, never()).createRecording(any(), any());
  }

  @Test
  void failedRestartIsRetriedWhenTheLastAppliedConfigurationComesBack() throws Exception {
    // A starts, B fails to start after A was stopped, then A is back: the profiler is stopped, so
    // A has to be applied again although it is the last one that was applied
    when(controller.createRecording(any(), any()))
        .thenReturn(recording)
        .thenThrow(new IllegalStateException("boom"))
        .thenReturn(recording);
    ProfilingRemoteConfigHandler handler = handler();
    handler.onConfigsChanged(enabledConfig());
    assertTrue(profilingSystem.isRunning());

    handler.onConfigsChanged(otherEnabledConfig());
    assertFalse(profilingSystem.isRunning(), "B failed to start");

    handler.onConfigsChanged(enabledConfig());

    assertTrue(profilingSystem.isRunning());
    verify(controller, times(3)).createRecording(any(), any());
  }

  @Test
  void startThatFailsWithoutThrowingIsNotTakenForApplied() throws Exception {
    // the profiling system only logs an unsupported environment
    when(controller.createRecording(any(), any()))
        .thenThrow(new UnsupportedEnvironmentException("unsupported"))
        .thenReturn(recording);
    ProfilingRemoteConfigHandler handler = handler();

    handler.onConfigsChanged(enabledConfig());
    assertFalse(profilingSystem.isRunning(), "not left half started");
    assertFalse(profilingSystem.isStarted());

    // the same configuration is applied again, and this time it works
    handler.onConfigsChanged(enabledConfig());

    assertTrue(profilingSystem.isRunning());
    assertTrue(profilingSystem.isStarted());
    verify(controller, times(2)).createRecording(any(), any());
  }

  @Test
  void startFailureIsNotPropagated() throws Exception {
    when(controller.createRecording(any(), any())).thenThrow(new IllegalStateException("boom"));
    ProfilingRemoteConfigHandler handler = handler();

    assertDoesNotThrow(() -> handler.onConfigsChanged(enabledConfig()));

    assertFalse(profilingSystem.isRunning());
  }

  @Test
  void configsBeforeProfilerIsSetAreAppliedOnceItIs() throws Exception {
    ProfilingRemoteConfigHandler handler = handler(null);

    handler.onConfigsChanged(enabledConfig());
    assertFalse(profilingSystem.isRunning());
    assertTrue(createdConfigs.isEmpty());

    handler.setProfilingSystem(profilingSystem);

    assertTrue(profilingSystem.isRunning());
    assertEquals(1, createdConfigs.size());
    verify(controller).reconfigure(createdConfigs.get(0));
    verify(controller).createRecording(any(), any());
  }

  @Test
  void onlyTheLatestConfigsReceivedBeforeProfilerIsSetAreApplied() throws Exception {
    ProfilingRemoteConfigHandler handler = handler(null);

    handler.onConfigsChanged(enabledConfig());
    handler.onConfigsChanged(disabledConfig());
    handler.setProfilingSystem(profilingSystem);

    assertFalse(profilingSystem.isRunning());
    verify(controller, never()).createRecording(any(), any());
  }

  @Test
  void configsReceivedBeforeProfilerIsSetAreNotAppliedOnceClosed() throws Exception {
    ProfilingRemoteConfigHandler handler = handler(null);
    handler.onConfigsChanged(enabledConfig());

    handler.close();
    handler.setProfilingSystem(profilingSystem);

    assertFalse(profilingSystem.isRunning());
    verify(controller, never()).createRecording(any(), any());
  }

  @Test
  void settingTheProfilerWithoutConfigsLeavesItAlone() throws Exception {
    ProfilingRemoteConfigHandler handler = handler(null);

    handler.setProfilingSystem(profilingSystem);

    assertFalse(profilingSystem.isRunning());
    assertTrue(createdConfigs.isEmpty());
    verify(controller, never()).reconfigure(any());
  }

  @Test
  void configsAfterProfilerIsSetAreApplied() throws Exception {
    ProfilingRemoteConfigHandler handler = handler(null);
    handler.onConfigsChanged(disabledConfig());
    handler.setProfilingSystem(profilingSystem);

    handler.onConfigsChanged(enabledConfig());

    assertTrue(profilingSystem.isRunning());
    assertEquals(2, createdConfigs.size(), "the one received early, and this one");
    verify(controller).reconfigure(createdConfigs.get(1));
  }

  @Test
  void profilerStartsAfterTheStartupDelayWhenStartingRightAwayIsNotSafe() throws Exception {
    ProfilingSystem system = systemWithStartupDelay(Duration.ofHours(1));
    handler(system, false).onConfigsChanged(enabledConfig());

    // it is starting, but only records after the startup delay, which isn't a failure to start
    assertTrue(system.isRunning());
    assertFalse(system.isStarted());
    verify(controller).reconfigure(createdConfigs.get(0));
    verify(controller, never()).createRecording(any(), any());
    system.stop(false);
  }

  @Test
  void profilerStartsAfterTheStartupDelayEvenWhenStartingRightAwayIsSafe() throws Exception {
    ProfilingSystem system = systemWithStartupDelay(Duration.ofHours(1));
    handler(system, true).onConfigsChanged(enabledConfig());

    assertTrue(system.isRunning());
    assertFalse(system.isStarted(), "the configured startup delay is not over yet");
    verify(controller, never()).createRecording(any(), any());
    system.stop(false);
  }

  @Test
  void configChangesBeforeTheFirstRecordingDoNotDelayItAgain() throws Exception {
    ProfilingSystem system = systemWithStartupDelay(Duration.ofMillis(600));
    ProfilingRemoteConfigHandler handler = handler(system, false);
    long startNanos = System.nanoTime();
    handler.onConfigsChanged(enabledConfig());

    // restarts before the first recording, which keep the deadline of the first start
    Thread.sleep(300);
    handler.onConfigsChanged(otherEnabledConfig());
    awaitTrue(system::isStarted);

    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    assertTrue(elapsedMillis < 850, "started after " + elapsedMillis + "ms, not a new delay");
    system.stop(false);
  }

  @Test
  void aStartThatFailsAfterTheStartupDelayIsAppliedAgainByTheSameConfig() throws Exception {
    when(controller.createRecording(any(), any()))
        .thenThrow(new IllegalArgumentException("boom"))
        .thenReturn(recording);
    ProfilingSystem system = systemWithStartupDelay(Duration.ofMillis(20));
    ProfilingRemoteConfigHandler handler = handler(system, false);

    handler.onConfigsChanged(enabledConfig());
    awaitTrue(system::isStartFailed);
    // the same config: the handler is not told that the start failed, but the system is
    handler.onConfigsChanged(enabledConfig());

    assertEquals(2, createdConfigs.size(), "applied again, although it is the same configuration");
    awaitTrue(system::isStarted);
    system.stop(false);
  }

  @Test
  void restartsRightAwayOnceARecordingRanEvenWhenStartingFirstIsNotSafe() throws Exception {
    ProfilingSystem system = systemWithStartupDelay(Duration.ofMillis(300));
    ProfilingRemoteConfigHandler handler = handler(system, false);

    handler.onConfigsChanged(enabledConfig());
    assertFalse(system.isStarted(), "the first start waits for the startup delay");
    awaitTrue(system::isStarted);

    handler.onConfigsChanged(otherEnabledConfig());

    // the JVM is past premain by then: no new startup delay
    assertTrue(system.isStarted());
    system.stop(false);
  }

  private ProfilingSystem systemWithStartupDelay(Duration startupDelay) throws Exception {
    return new ProfilingSystem(
        localConfig,
        context -> controller,
        mock(RecordingDataListener.class),
        startupDelay,
        Duration.ofMillis(1),
        Duration.ofHours(1),
        false);
  }

  private static void awaitTrue(java.util.function.BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not met in time");
      }
      Thread.sleep(5);
    }
  }

  @Test
  void stoppingTheProfilerFlushesWhatItRecorded() throws Exception {
    ProfilingRemoteConfigHandler handler = handler();
    handler.onConfigsChanged(enabledConfig());

    handler.onConfigsChanged(disabledConfig());

    assertFalse(profilingSystem.isRunning());
    verify(recording).snapshot(any(), eq(ProfilingSnapshot.Kind.PERIODIC));
  }

  @Test
  void restartingTheProfilerFlushesWhatItRecorded() throws Exception {
    ProfilingRemoteConfigHandler handler = handler();
    handler.onConfigsChanged(enabledConfig());

    handler.onConfigsChanged(otherEnabledConfig());

    assertTrue(profilingSystem.isRunning());
    verify(recording).snapshot(any(), eq(ProfilingSnapshot.Kind.PERIODIC));
  }

  @Test
  void closingDoesNotWaitForAConfigurationBeingAppliedAndKeepsItFromStartingTheProfiler()
      throws Exception {
    // the restart holds the handler, and closing must not wait for it
    whileARestartIsStopping(ProfilingRemoteConfigHandler::close);

    assertFalse(profilingSystem.isRunning(), "closed during the restart, so it didn't start");
    verify(controller, times(1)).createRecording(any(), any());
  }

  @Test
  void configsReceivedWhileOthersAreAppliedDoNotWaitAndAreAppliedAfterThem() throws Exception {
    // left to the thread applying the restart, instead of waiting for it
    whileARestartIsStopping(handler -> handler.onConfigsChanged(disabledConfig()));

    assertFalse(profilingSystem.isRunning(), "the latest configs, applied after the restart");
    verify(controller, times(2)).createRecording(any(), any());
  }

  /**
   * Starts the profiler, restarts it on another thread, and runs the given action, which must not
   * wait for it, while the restart is uploading its final snapshot.
   */
  private void whileARestartIsStopping(Consumer<ProfilingRemoteConfigHandler> action)
      throws Exception {
    CountDownLatch stopping = new CountDownLatch(1);
    CountDownLatch proceed = new CountDownLatch(1);
    when(recording.snapshot(any(), any()))
        .thenAnswer(invocation -> {
          stopping.countDown();
          proceed.await(10, TimeUnit.SECONDS);
          return null;
        });
    ProfilingRemoteConfigHandler handler = handler();
    handler.onConfigsChanged(enabledConfig());
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<?> restart = executor.submit(() -> handler.onConfigsChanged(otherEnabledConfig()));
      assertTrue(stopping.await(10, TimeUnit.SECONDS), "the restart is stopping the profiler");

      assertTimeoutPreemptively(Duration.ofSeconds(5), () -> action.accept(handler));
      proceed.countDown();
      restart.get(10, TimeUnit.SECONDS);
    } finally {
      proceed.countDown();
      executor.shutdownNow();
    }
  }

  private static RemoteConfiguration config(String profilers) {
    return RemoteConfiguration.parse("{\"profilers\":{" + profilers + "}}");
  }

  private static RemoteConfigurationStack stackOf(String profilers) {
    RemoteConfigurationStack stack = new RemoteConfigurationStack();
    stack.push("config-1", 0, config(profilers));
    return stack;
  }

  private static RemoteConfigurationStack enabledConfig() {
    return stackOf("\"cpuTime\":true");
  }

  private static RemoteConfigurationStack otherEnabledConfig() {
    return stackOf("\"cpuTime\":true,\"wallTime\":true");
  }

  private static RemoteConfigurationStack disabledConfig() {
    return stackOf("\"cpuTime\":false");
  }
}
