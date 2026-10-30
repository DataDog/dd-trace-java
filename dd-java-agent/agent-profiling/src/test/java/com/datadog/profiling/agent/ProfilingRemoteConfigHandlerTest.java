package com.datadog.profiling.agent;

import static com.datadog.profiling.agent.ProfilingRemoteConfigHandler.DISABLE_CONFIG_ID;
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
import java.util.HashMap;
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
    handler(false).onConfigsChanged(null, ENABLED_CONFIG);

    assertTrue(profilingSystem.isRunning());
    assertTrue(profilingSystem.isStarted(), "started immediately, without the startup delay");
    assertEquals(1, createdConfigs.size());
    verify(controller).reconfigure(createdConfigs.get(0));
    verify(controller).createRecording(any(), any());
  }

  @Test
  void idleStaysIdleWhenRemoteConfigDisablesEverything() throws Exception {
    handler(false).onConfigsChanged(null, DISABLED_CONFIG);

    assertFalse(profilingSystem.isRunning());
    verify(controller, never()).reconfigure(any());
    verify(controller, never()).createRecording(any(), any());
  }

  @Test
  void runningRestartsWithNewConfiguration() throws Exception {
    ProfilingRemoteConfigHandler handler = handler(false);
    handler.onConfigsChanged(null, ENABLED_CONFIG);

    handler.onConfigsChanged(null, OTHER_ENABLED_CONFIG);

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
    handler.onConfigsChanged(null, ENABLED_CONFIG);

    handler.onConfigsChanged(null, DISABLED_CONFIG);

    assertFalse(profilingSystem.isRunning());
    verify(recording).stop();
    verify(controller, times(1)).reconfigure(any());
    verify(controller, times(1)).createRecording(any(), any());
  }

  @Test
  void runningStopsAndAppliesDisableConfig() throws Exception {
    ProfilingRemoteConfigHandler handler = handler(false);
    handler.onConfigsChanged(null, ENABLED_CONFIG);

    handler.onConfigsChanged(DISABLE_CONFIG_ID, withDisableConfig(ENABLED_CONFIG));

    assertFalse(profilingSystem.isRunning());
    assertEquals(2, createdConfigs.size());
    verify(recording).stop();
    verify(controller).reconfigure(createdConfigs.get(1));
    verify(controller, times(1)).createRecording(any(), any());
  }

  @Test
  void idleAppliesDisableConfigWithoutStarting() throws Exception {
    handler(false).onConfigsChanged(DISABLE_CONFIG_ID, withDisableConfig(ENABLED_CONFIG));

    assertFalse(profilingSystem.isRunning());
    assertEquals(1, createdConfigs.size());
    verify(controller).reconfigure(createdConfigs.get(0));
    verify(controller, never()).createRecording(any(), any());
  }

  @Test
  void removingDisableConfigRestartsProfiling() throws Exception {
    ProfilingRemoteConfigHandler handler = handler(false);
    handler.onConfigsChanged(DISABLE_CONFIG_ID, withDisableConfig(ENABLED_CONFIG));

    // same merged values as before, only the disable config is gone
    handler.onConfigsChanged(null, ENABLED_CONFIG);

    assertTrue(profilingSystem.isRunning());
    verify(controller, times(1)).createRecording(any(), any());
  }

  @Test
  void removingRemoteConfigRevertsToIdleWhenArmed() throws Exception {
    ProfilingRemoteConfigHandler handler = handler(false);
    handler.onConfigsChanged(null, ENABLED_CONFIG);

    handler.onConfigsChanged(null, emptyMap());

    assertFalse(profilingSystem.isRunning());
    verify(recording).stop();
  }

  @Test
  void removingRemoteConfigRestartsWithLocalConfiguration() throws Exception {
    ProfilingRemoteConfigHandler handler = handler(true);
    handler.onConfigsChanged(null, ENABLED_CONFIG);

    handler.onConfigsChanged(null, emptyMap());

    assertTrue(profilingSystem.isRunning());
    verify(recording).stop();
    verify(controller).reconfigure(localConfig);
    verify(controller, times(2)).createRecording(any(), any());
  }

  @Test
  void unchangedRemoteConfigIsIgnored() throws Exception {
    ProfilingRemoteConfigHandler handler = handler(false);
    handler.onConfigsChanged(null, ENABLED_CONFIG);

    handler.onConfigsChanged(null, ENABLED_CONFIG);

    assertTrue(profilingSystem.isRunning());
    assertEquals(1, createdConfigs.size());
    verify(recording, never()).stop();
    verify(controller, times(1)).createRecording(any(), any());
  }

  @Test
  void noRemoteConfigInitiallyIsIgnored() throws Exception {
    handler(false).onConfigsChanged(null, emptyMap());

    assertFalse(profilingSystem.isRunning());
    verify(controller, never()).reconfigure(any());
  }

  @Test
  void closedHandlerIgnoresRemoteConfig() throws Exception {
    ProfilingRemoteConfigHandler handler = handler(false);
    handler.close();

    handler.onConfigsChanged(null, ENABLED_CONFIG);

    assertFalse(profilingSystem.isRunning());
    verify(controller, never()).createRecording(any(), any());
  }

  @Test
  void startFailureIsNotPropagated() throws Exception {
    when(controller.createRecording(any(), any())).thenThrow(new IllegalStateException("boom"));
    ProfilingRemoteConfigHandler handler = handler(false);

    assertDoesNotThrow(() -> handler.onConfigsChanged(null, ENABLED_CONFIG));

    assertFalse(profilingSystem.isRunning());
  }

  @Test
  void configsBeforeProfilerIsSetAreDropped() throws Exception {
    ProfilingRemoteConfigHandler handler = handlerWithoutProfiler(false);

    handler.onConfigsChanged(null, ENABLED_CONFIG);
    handler.setProfilingSystem(profilingSystem);

    assertFalse(profilingSystem.isRunning());
    assertTrue(createdConfigs.isEmpty());
    verify(controller, never()).createRecording(any(), any());
  }

  @Test
  void configsAfterProfilerIsSetAreApplied() throws Exception {
    ProfilingRemoteConfigHandler handler = handlerWithoutProfiler(false);
    handler.onConfigsChanged(null, DISABLED_CONFIG);
    handler.setProfilingSystem(profilingSystem);

    handler.onConfigsChanged(null, ENABLED_CONFIG);

    assertTrue(profilingSystem.isRunning());
    assertEquals(1, createdConfigs.size());
    verify(controller).reconfigure(createdConfigs.get(0));
  }

  private static Map<String, String> withDisableConfig(Map<String, String> configs) {
    Map<String, String> result = new HashMap<>(configs);
    result.put(DISABLE_CONFIG_ID, "{}");
    return result;
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
