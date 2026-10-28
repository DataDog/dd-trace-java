package com.datadog.profiling.agent;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datadog.profiling.controller.Controller;
import com.datadog.profiling.controller.OngoingRecording;
import com.datadog.profiling.controller.ProfilingSystem;
import datadog.trace.api.profiling.RecordingDataListener;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProfilingRemoteConfigHandlerTest {
  private static final String ENABLED_KEY = "profiling.ddprof.cpu.enabled";
  private static final Map<String, String> ENABLED_CONFIG =
      singletonMap("config-1", "{\"" + ENABLED_KEY + "\": true}");
  private static final Map<String, String> OTHER_ENABLED_CONFIG =
      singletonMap("config-1", "{\"" + ENABLED_KEY + "\": true, \"other\": 1}");
  private static final Map<String, String> DISABLED_CONFIG =
      singletonMap("config-1", "{\"" + ENABLED_KEY + "\": false}");

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
            Duration.ofHours(1),
            Duration.ofMillis(1),
            Duration.ofHours(1),
            false);
  }

  @AfterEach
  void tearDown() {
    profilingSystem.stop(false);
  }

  private ProfilingRemoteConfigHandler handler(boolean runWithLocalConfig) {
    return new ProfilingRemoteConfigHandler(
        profilingSystem,
        localConfig,
        runWithLocalConfig,
        values -> {
          ConfigProvider config = ConfigProvider.withRemoteOverride(values);
          remoteValues.put(config, values);
          createdConfigs.add(config);
          return config;
        },
        config -> "true".equals(remoteValues.getOrDefault(config, emptyMap()).get(ENABLED_KEY)));
  }

  @Test
  void idleStartsWhenRemoteConfigEnablesProfiling() throws Exception {
    handler(false).onConfigsChanged(ENABLED_CONFIG);

    assertTrue(profilingSystem.isRunning());
    assertTrue(profilingSystem.isStarted(), "started immediately, without the startup delay");
    assertEquals(1, createdConfigs.size());
    verify(controller).reconfigure(createdConfigs.get(0));
    verify(controller).createRecording(any(), any());
  }

  @Test
  void idleStaysIdleWhenRemoteConfigDisablesEverything() throws Exception {
    handler(false).onConfigsChanged(DISABLED_CONFIG);

    assertFalse(profilingSystem.isRunning());
    verify(controller, never()).reconfigure(any());
    verify(controller, never()).createRecording(any(), any());
  }

  @Test
  void runningRestartsWithNewConfiguration() throws Exception {
    ProfilingRemoteConfigHandler handler = handler(false);
    handler.onConfigsChanged(ENABLED_CONFIG);

    handler.onConfigsChanged(OTHER_ENABLED_CONFIG);

    assertTrue(profilingSystem.isRunning());
    assertEquals(2, createdConfigs.size());
    verify(recording).stop();
    verify(controller).reconfigure(createdConfigs.get(1));
    verify(controller, times(2)).createRecording(any(), any());
    assertEquals("1", remoteValues.get(createdConfigs.get(1)).get("other"));
  }

  @Test
  void runningStopsWhenRemoteConfigDisablesEverything() throws Exception {
    ProfilingRemoteConfigHandler handler = handler(false);
    handler.onConfigsChanged(ENABLED_CONFIG);

    handler.onConfigsChanged(DISABLED_CONFIG);

    assertFalse(profilingSystem.isRunning());
    verify(recording).stop();
    verify(controller, times(1)).reconfigure(any());
    verify(controller, times(1)).createRecording(any(), any());
  }

  @Test
  void removingRemoteConfigRevertsToIdleWhenArmed() throws Exception {
    ProfilingRemoteConfigHandler handler = handler(false);
    handler.onConfigsChanged(ENABLED_CONFIG);

    handler.onConfigsChanged(emptyMap());

    assertFalse(profilingSystem.isRunning());
    verify(recording).stop();
  }

  @Test
  void removingRemoteConfigRestartsWithLocalConfiguration() throws Exception {
    ProfilingRemoteConfigHandler handler = handler(true);
    handler.onConfigsChanged(ENABLED_CONFIG);

    handler.onConfigsChanged(emptyMap());

    assertTrue(profilingSystem.isRunning());
    verify(recording).stop();
    verify(controller).reconfigure(localConfig);
    verify(controller, times(2)).createRecording(any(), any());
  }

  @Test
  void unchangedRemoteConfigIsIgnored() throws Exception {
    ProfilingRemoteConfigHandler handler = handler(false);
    handler.onConfigsChanged(ENABLED_CONFIG);

    handler.onConfigsChanged(ENABLED_CONFIG);

    assertTrue(profilingSystem.isRunning());
    assertEquals(1, createdConfigs.size());
    verify(recording, never()).stop();
    verify(controller, times(1)).createRecording(any(), any());
  }

  @Test
  void noRemoteConfigInitiallyIsIgnored() throws Exception {
    handler(false).onConfigsChanged(emptyMap());

    assertFalse(profilingSystem.isRunning());
    verify(controller, never()).reconfigure(any());
  }

  @Test
  void closedHandlerIgnoresRemoteConfig() throws Exception {
    ProfilingRemoteConfigHandler handler = handler(false);
    handler.close();

    handler.onConfigsChanged(ENABLED_CONFIG);

    assertFalse(profilingSystem.isRunning());
    verify(controller, never()).createRecording(any(), any());
  }

  @Test
  void startFailureIsNotPropagated() throws Exception {
    when(controller.createRecording(any(), any())).thenThrow(new IllegalStateException("boom"));
    ProfilingRemoteConfigHandler handler = handler(false);

    assertDoesNotThrow(() -> handler.onConfigsChanged(ENABLED_CONFIG));

    assertFalse(profilingSystem.isRunning());
  }

  @Test
  void configsAreBufferedUntilProfilerIsSet() throws Exception {
    ProfilingRemoteConfigHandler handler = handlerWithoutProfiler(false);

    // Config arrives before profiler is available
    handler.onConfigsChanged(ENABLED_CONFIG);

    // Profiler not running yet because it wasn't set
    assertFalse(profilingSystem.isRunning());
    verify(controller, never()).createRecording(any(), any());

    // Now set the profiler - buffered config should be applied
    handler.setProfilingSystem(profilingSystem);

    assertTrue(profilingSystem.isRunning());
    assertTrue(profilingSystem.isStarted());
    assertEquals(1, createdConfigs.size());
    verify(controller).reconfigure(createdConfigs.get(0));
    verify(controller).createRecording(any(), any());
  }

  @Test
  void laterConfigReplacesBufferedConfig() throws Exception {
    ProfilingRemoteConfigHandler handler = handlerWithoutProfiler(false);

    // Multiple configs arrive before profiler is available
    handler.onConfigsChanged(DISABLED_CONFIG);
    handler.onConfigsChanged(ENABLED_CONFIG);

    // Now set the profiler - only the latest config should be applied
    handler.setProfilingSystem(profilingSystem);

    assertTrue(profilingSystem.isRunning());
    assertEquals(1, createdConfigs.size());
  }

  private ProfilingRemoteConfigHandler handlerWithoutProfiler(boolean runWithLocalConfig) {
    return new ProfilingRemoteConfigHandler(
        null, // no profiler initially
        localConfig,
        runWithLocalConfig,
        values -> {
          ConfigProvider config = ConfigProvider.withRemoteOverride(values);
          remoteValues.put(config, values);
          createdConfigs.add(config);
          return config;
        },
        config -> "true".equals(remoteValues.getOrDefault(config, emptyMap()).get(ENABLED_KEY)));
  }
}
