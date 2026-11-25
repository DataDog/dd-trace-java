package com.datadog.profiling.agent;

import static com.datadog.profiling.controller.ProfilingSupport.isAllocationSamplerSafe;
import static com.datadog.profiling.ddprof.DatadogProfilerConfig.isAllocationProfilingEnabled;
import static com.datadog.profiling.ddprof.DatadogProfilerConfig.isCpuProfilerEnabled;
import static com.datadog.profiling.ddprof.DatadogProfilerConfig.isLiveHeapSizeTrackingEnabled;
import static com.datadog.profiling.ddprof.DatadogProfilerConfig.isMemoryLeakProfilingEnabled;
import static com.datadog.profiling.ddprof.DatadogProfilerConfig.isWallClockProfilerEnabled;
import static com.datadog.profiling.remoteconfig.TestConfigKey.configKey;
import static com.datadog.profiling.utils.ProfilingMode.ALLOCATION;
import static com.datadog.profiling.utils.ProfilingMode.CPU;
import static com.datadog.profiling.utils.ProfilingMode.LIVEHEAP_SIZE;
import static com.datadog.profiling.utils.ProfilingMode.MEMLEAK;
import static com.datadog.profiling.utils.ProfilingMode.WALL;
import static datadog.environment.JavaVirtualMachine.isJ9;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ALLOCATION_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_CPU_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_LIVEHEAP_TRACK_HEAPSIZE;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_CONTEXT_FILTER;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_HEAP_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ULTRA_MINIMAL;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_ENABLED;
import static java.lang.Boolean.TRUE;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
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
import com.datadog.profiling.utils.ProfilingMode;
import datadog.trace.api.ConfigCollector;
import datadog.trace.api.ConfigOrigin;
import datadog.trace.api.ConfigSetting;
import datadog.trace.api.profiling.ProfilingSnapshot;
import datadog.trace.api.profiling.RecordingDataListener;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ProfilingRemoteConfigHandlerTest {
  private static final String ALL_FEATURES_DISABLED =
      "\"cpuTime\":false,\"wallTime\":false,\"heapObjects\":false,\"heapSize\":false,"
          + "\"allocations\":false";

  private final ConfigProvider localConfig = ConfigProvider.getInstance();
  private final Controller controller = mock(Controller.class);
  private final OngoingRecording recording = mock(OngoingRecording.class);
  // the mode overrides of each configuration checked for enabled profiling, in order
  private final List<Map<ProfilingMode, Boolean>> appliedOverrides = new ArrayList<>();

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
        profilingSystem, localConfig, DatadogProfiler::isAnyProfilingModeEnabled, true);
  }

  private ProfilingRemoteConfigHandler handler(ProfilingSystem system) {
    return handler(system, true);
  }

  /**
   * A handler that records the mode overrides it checks, and for which only cpuTime enables
   * anything.
   *
   * @param startImmediately whether starting right away is safe
   */
  private ProfilingRemoteConfigHandler handler(ProfilingSystem system, boolean startImmediately) {
    return new ProfilingRemoteConfigHandler(
        system,
        localConfig,
        (config, overrides) -> {
          appliedOverrides.add(overrides);
          return TRUE.equals(overrides.get(CPU));
        },
        startImmediately);
  }

  @Test
  void idleStartsWhenRemoteConfigEnablesProfiling() throws Exception {
    handler().onConfigsChanged(enabledConfig());

    assertTrue(profilingSystem.isRunning());
    assertTrue(profilingSystem.isStarted(), "started immediately, without the startup delay");
    assertEquals(1, appliedOverrides.size());
    verify(controller).reconfigure(localConfig, appliedOverrides.get(0));
    verify(controller).createRecording(any(), any());
  }

  @Test
  void idleStaysIdleWhenRemoteConfigDisablesEverything() throws Exception {
    handler().onConfigsChanged(disabledConfig());

    assertFalse(profilingSystem.isRunning());
    verify(controller, never()).reconfigure(any(), any());
    verify(controller, never()).createRecording(any(), any());
  }

  @Test
  void runningRestartsWithNewConfiguration() throws Exception {
    ProfilingRemoteConfigHandler handler = handler();
    handler.onConfigsChanged(enabledConfig());

    handler.onConfigsChanged(otherEnabledConfig());

    assertTrue(profilingSystem.isRunning());
    assertEquals(2, appliedOverrides.size());
    verify(recording).stop();
    verify(controller).reconfigure(localConfig, appliedOverrides.get(1));
    verify(controller, times(2)).createRecording(any(), any());
    assertEquals(true, appliedOverrides.get(1).get(WALL));
  }

  @Test
  void runningStopsWhenRemoteConfigDisablesEverything() throws Exception {
    ProfilingRemoteConfigHandler handler = handler();
    handler.onConfigsChanged(enabledConfig());

    handler.onConfigsChanged(disabledConfig());

    assertFalse(profilingSystem.isRunning());
    verify(recording).stop();
    verify(controller, times(1)).reconfigure(any(), any());
    verify(controller, times(1)).createRecording(any(), any());
  }

  @Test
  void runningStopsWhenAHigherPriorityConfigDisablesWhatTheOthersEnable() throws Exception {
    ProfilingRemoteConfigHandler handler = handler();
    handler.onConfigsChanged(enabledConfig());

    // the merged configuration has the only feature that was enabled disabled
    RemoteConfigurationStack stack = new RemoteConfigurationStack();
    stack.push(configKey("general"), 0, config("\"cpuTime\":true"));
    stack.push(configKey("specific"), 3001, config("\"cpuTime\":false"));
    handler.onConfigsChanged(stack);

    assertFalse(profilingSystem.isRunning());
    verify(recording).stop();
    verify(controller, times(1)).createRecording(any(), any());
  }

  @Test
  void highestPriorityConfigDecidesWhateverTheConfigId() throws Exception {
    // by config id, "z" would overwrite "a": it has the lower priority, so it must not
    RemoteConfigurationStack stack = new RemoteConfigurationStack();
    stack.push(configKey("a"), 3001, config("\"cpuTime\":true"));
    stack.push(configKey("z"), 0, config("\"cpuTime\":false"));

    handler().onConfigsChanged(stack);

    assertTrue(profilingSystem.isRunning());
    assertEquals(true, appliedOverrides.get(0).get(CPU));
  }

  @Test
  void configurationsAreMergedFeatureByFeature() throws Exception {
    RemoteConfigurationStack stack = new RemoteConfigurationStack();
    stack.push(configKey("general"), 0, config("\"cpuTime\":true,\"wallTime\":false"));
    stack.push(configKey("specific"), 2001, config("\"wallTime\":true"));

    handler().onConfigsChanged(stack);

    Map<ProfilingMode, Boolean> applied = appliedOverrides.get(0);
    assertEquals(true, applied.get(CPU), "from the general configuration");
    assertEquals(true, applied.get(WALL), "from the specific configuration");
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

  /** A handler for which the local configuration keeps something enabled, e.g. native memory. */
  private ProfilingRemoteConfigHandler handlerWhereTheLocalConfigEnablesProfiling() {
    return new ProfilingRemoteConfigHandler(
        profilingSystem, localConfig, (config, overrides) -> true, true);
  }

  @Test
  void disablingEveryFeatureStopsTheProfilerWhateverTheLocalConfigEnables() throws Exception {
    ProfilingRemoteConfigHandler handler = handlerWhereTheLocalConfigEnablesProfiling();
    handler.onConfigsChanged(enabledConfig());
    assertTrue(profilingSystem.isRunning());

    handler.onConfigsChanged(stackOf(ALL_FEATURES_DISABLED));

    assertFalse(profilingSystem.isRunning());
    verify(recording).stop();
    verify(controller, times(1)).createRecording(any(), any());
  }

  @Test
  void disablingEveryFeatureAcrossConfigsStopsTheProfilerWhateverTheLocalConfigEnables()
      throws Exception {
    ProfilingRemoteConfigHandler handler = handlerWhereTheLocalConfigEnablesProfiling();
    handler.onConfigsChanged(enabledConfig());
    RemoteConfigurationStack stack = new RemoteConfigurationStack();
    stack.push(configKey("config-1"), 1, config("\"cpuTime\":false,\"wallTime\":false"));
    stack.push(
        configKey("config-2"),
        0,
        config("\"heapObjects\":false,\"heapSize\":false,\"allocations\":false"));

    handler.onConfigsChanged(stack);

    assertFalse(profilingSystem.isRunning());
  }

  @Test
  void disablingEveryFeatureKeepsTheProfilerIdleWhateverTheLocalConfigEnables() throws Exception {
    handlerWhereTheLocalConfigEnablesProfiling().onConfigsChanged(stackOf(ALL_FEATURES_DISABLED));

    assertFalse(profilingSystem.isRunning());
    verify(controller, never()).reconfigure(any(), any());
    verify(controller, never()).createRecording(any(), any());
  }

  @Test
  void disablingSomeFeaturesKeepsWhatTheLocalConfigEnables() throws Exception {
    ProfilingRemoteConfigHandler handler = handlerWhereTheLocalConfigEnablesProfiling();
    handler.onConfigsChanged(enabledConfig());

    handler.onConfigsChanged(stackOf("\"cpuTime\":false,\"wallTime\":false,\"heapObjects\":false"));

    assertTrue(profilingSystem.isRunning());
  }

  @Test
  void featuresSwitchTheirModes() {
    Map<ProfilingMode, Boolean> overrides =
        ProfilingRemoteConfigHandler.modeOverrides(
            config("\"cpuTime\":true,\"wallTime\":false,\"heapObjects\":false,\"heapSize\":false"),
            localConfig());

    Map<ProfilingMode, Boolean> expected = new EnumMap<>(ProfilingMode.class);
    expected.put(CPU, true);
    expected.put(WALL, false);
    expected.put(MEMLEAK, false);
    expected.put(LIVEHEAP_SIZE, false);
    assertEquals(expected, overrides);
  }

  @Test
  void featuresNotSetDoNotOverrideTheirModes() {
    assertEquals(
        singletonMap(CPU, true),
        ProfilingRemoteConfigHandler.modeOverrides(config("\"cpuTime\":true"), localConfig()));
  }

  @Test
  void remoteEnablementIsOnlyKeptWhereItIsSafe() {
    Map<ProfilingMode, Boolean> overrides =
        ProfilingRemoteConfigHandler.modeOverrides(
            config("\"cpuTime\":true,\"allocations\":true,\"heapObjects\":true,\"heapSize\":true"),
            localConfig());

    // the local configuration decides where it isn't safe
    assertEquals(isAllocationSamplerSafe(), overrides.containsKey(ALLOCATION));
    assertEquals(isAllocationSamplerSafe(), overrides.containsKey(MEMLEAK));
    assertEquals(isAllocationSamplerSafe(), overrides.containsKey(LIVEHEAP_SIZE));
    assertEquals(true, overrides.get(CPU), "always safe");
  }

  @Test
  void remoteDisablementIsAlwaysKept() {
    Map<ProfilingMode, Boolean> overrides =
        ProfilingRemoteConfigHandler.modeOverrides(
            config("\"allocations\":false,\"heapObjects\":false,\"heapSize\":false"),
            localConfig());

    assertEquals(false, overrides.get(ALLOCATION));
    assertEquals(false, overrides.get(MEMLEAK));
    assertEquals(false, overrides.get(LIVEHEAP_SIZE));
  }

  /**
   * Like the local default, a remote enablement of wall clock profiling is ignored where it is
   * disabled unless opted in, e.g. in ultra minimal mode or without tracing, and on J9.
   */
  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "default,          false, true,  true",
    "ultra minimal,    true,  true,  false",
    "tracing disabled, false, false, false"
  })
  void remoteWallClockEnablementIsOnlyKeptWhereItIsEnabledByDefault(
      String scenario, String ultraMinimal, String tracing, boolean kept) {
    Properties properties = new Properties();
    properties.put(PROFILING_ULTRA_MINIMAL, ultraMinimal);
    properties.put(TRACE_ENABLED, tracing);
    ConfigProvider local = ConfigProvider.withPropertiesOverride(properties);

    Map<ProfilingMode, Boolean> enabled =
        ProfilingRemoteConfigHandler.modeOverrides(config("\"wallTime\":true"), local);
    Map<ProfilingMode, Boolean> disabled =
        ProfilingRemoteConfigHandler.modeOverrides(config("\"wallTime\":false"), local);

    // wall clock profiling is also only enabled by an opt-in on J9
    assertEquals(kept && !isJ9(), enabled.containsKey(WALL), String.valueOf(enabled));
    assertEquals(false, disabled.get(WALL), "disabling it is always allowed");
  }

  @Test
  void theModeOverridesAreAppliedOverTheLocalConfiguration() throws Exception {
    handler().onConfigsChanged(stackOf("\"cpuTime\":true,\"wallTime\":false"));

    Map<ProfilingMode, Boolean> expected = new EnumMap<>(ProfilingMode.class);
    expected.put(CPU, true);
    expected.put(WALL, false);
    verify(controller).reconfigure(localConfig, expected);
  }

  @Test
  void appliedValuesAreReportedToTelemetryWithTheRemoteConfigOrigin() throws Exception {
    ConfigCollector.get().collect();

    handler().onConfigsChanged(stackOf("\"cpuTime\":true,\"wallTime\":false,\"heapSize\":false"));

    Map<String, ConfigSetting> reported = ConfigCollector.get().collect().get(ConfigOrigin.REMOTE);
    assertEquals(true, reported.get(PROFILING_DATADOG_PROFILER_CPU_ENABLED).value);
    assertEquals(false, reported.get(PROFILING_DATADOG_PROFILER_WALL_ENABLED).value);
    assertEquals(false, reported.get(PROFILING_DATADOG_PROFILER_LIVEHEAP_TRACK_HEAPSIZE).value);
    // the settings it doesn't override are reported with their local value
    assertEquals(
        isAllocationProfilingEnabled(localConfig),
        reported.get(PROFILING_ALLOCATION_ENABLED).value);
    assertEquals(
        isMemoryLeakProfilingEnabled(localConfig), reported.get(PROFILING_HEAP_ENABLED).value);
    assertEquals(5, reported.size(), String.valueOf(reported));
  }

  /**
   * Once remote config no longer overrides a setting, telemetry is told its local value back,
   * instead of keeping the remote one.
   */
  @Test
  void removedOverridesAreReportedToTelemetryWithTheLocalValue() throws Exception {
    ProfilingRemoteConfigHandler handler = handler();
    ConfigCollector.get().collect();
    handler.onConfigsChanged(
        stackOf("\"cpuTime\":true,\"wallTime\":" + !isWallClockProfilerEnabled(localConfig)));
    ConfigCollector.get().collect();

    // the wall clock override is removed
    handler.onConfigsChanged(stackOf("\"cpuTime\":true"));

    Map<String, ConfigSetting> reported = ConfigCollector.get().collect().get(ConfigOrigin.REMOTE);
    assertEquals(true, reported.get(PROFILING_DATADOG_PROFILER_CPU_ENABLED).value);
    assertEquals(
        isWallClockProfilerEnabled(localConfig),
        reported.get(PROFILING_DATADOG_PROFILER_WALL_ENABLED).value);
  }

  @Test
  void removingEveryOverrideReportsTheLocalValuesToTelemetry() throws Exception {
    ProfilingRemoteConfigHandler handler = handlerWhereTheLocalConfigEnablesProfiling();
    ConfigCollector.get().collect();
    handler.onConfigsChanged(stackOf(ALL_FEATURES_DISABLED));
    ConfigCollector.get().collect();

    handler.onConfigsChanged(stackOfConfigsThatSetNoFeature());

    Map<String, ConfigSetting> reported = ConfigCollector.get().collect().get(ConfigOrigin.REMOTE);
    assertEquals(
        ProfilingRemoteConfigHandler.telemetryValues(emptyMap(), localConfig), valuesOf(reported));
  }

  @Test
  void telemetryValuesTakeTheOverridesOverTheLocalValues() {
    Map<ProfilingMode, Boolean> overrides = new EnumMap<>(ProfilingMode.class);
    overrides.put(CPU, !isCpuProfilerEnabled(localConfig));

    Map<String, Object> values =
        ProfilingRemoteConfigHandler.telemetryValues(overrides, localConfig);

    assertEquals(
        !isCpuProfilerEnabled(localConfig), values.get(PROFILING_DATADOG_PROFILER_CPU_ENABLED));
    assertEquals(
        isWallClockProfilerEnabled(localConfig),
        values.get(PROFILING_DATADOG_PROFILER_WALL_ENABLED));
    assertEquals(
        isAllocationProfilingEnabled(localConfig), values.get(PROFILING_ALLOCATION_ENABLED));
    assertEquals(isMemoryLeakProfilingEnabled(localConfig), values.get(PROFILING_HEAP_ENABLED));
    assertEquals(
        isLiveHeapSizeTrackingEnabled(localConfig),
        values.get(PROFILING_DATADOG_PROFILER_LIVEHEAP_TRACK_HEAPSIZE));
    assertEquals(5, values.size(), String.valueOf(values));
  }

  private static Map<String, Object> valuesOf(Map<String, ConfigSetting> settings) {
    Map<String, Object> values = new HashMap<>();
    settings.forEach((name, setting) -> values.put(name, setting.value));
    return values;
  }

  @Test
  void disablingEveryFeatureIsReportedToTelemetry() throws Exception {
    ConfigCollector.get().collect();

    handler().onConfigsChanged(stackOf(ALL_FEATURES_DISABLED));

    Map<String, ConfigSetting> reported = ConfigCollector.get().collect().get(ConfigOrigin.REMOTE);
    assertEquals(false, reported.get(PROFILING_DATADOG_PROFILER_CPU_ENABLED).value);
    assertEquals(false, reported.get(PROFILING_HEAP_ENABLED).value);
    assertEquals(false, reported.get(PROFILING_ALLOCATION_ENABLED).value);
    assertEquals(5, reported.size(), String.valueOf(reported));
  }

  @Test
  void configsThatFailToApplyAreNotReportedToTelemetry() throws Exception {
    when(controller.createRecording(any(), any())).thenThrow(new IllegalStateException("boom"));
    ConfigCollector.get().collect();

    handler().onConfigsChanged(enabledConfig());

    assertFalse(ConfigCollector.get().collect().containsKey(ConfigOrigin.REMOTE));
  }

  @Test
  void noProfilingConfigIsNotReportedToTelemetry() throws Exception {
    ConfigCollector.get().collect();

    handlerWhereTheLocalConfigEnablesProfiling().onConfigsChanged(stackOfConfigsThatSetNoFeature());

    assertFalse(ConfigCollector.get().collect().containsKey(ConfigOrigin.REMOTE));
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
    stack.push(configKey("general"), 0, config(ALL_FEATURES_DISABLED));
    stack.push(configKey("specific"), 3001, config("\"wallTime\":true"));

    handlerWithRealFeatureCheck().onConfigsChanged(stack);

    // only the wall time is enabled in the merged configuration
    assertTrue(profilingSystem.isRunning());
    verify(controller).createRecording(any(), any());
  }

  @Test
  void removingRemoteConfigRevertsToIdleWhenArmed() throws Exception {
    ProfilingRemoteConfigHandler handler = handlerWhereTheLocalConfigEnablesProfiling();
    handler.onConfigsChanged(enabledConfig());

    handler.onConfigsChanged(new RemoteConfigurationStack());

    // whatever the local configuration enables
    assertFalse(profilingSystem.isRunning());
    verify(recording).stop();
    verify(controller, times(1)).createRecording(any(), any());
  }

  @Test
  void configsThatSetNoFeatureApplyTheLocalConfigurationThatDisablesEverything() throws Exception {
    ProfilingRemoteConfigHandler handler = handler();
    handler.onConfigsChanged(enabledConfig());

    handler.onConfigsChanged(stackOfConfigsThatSetNoFeature());

    assertFalse(profilingSystem.isRunning());
    verify(recording).stop();
  }

  @Test
  void unchangedRemoteConfigIsIgnored() throws Exception {
    ProfilingRemoteConfigHandler handler = handler();
    handler.onConfigsChanged(enabledConfig());

    handler.onConfigsChanged(enabledConfig());

    assertTrue(profilingSystem.isRunning());
    assertEquals(1, appliedOverrides.size());
    verify(recording, never()).stop();
    verify(controller, times(1)).createRecording(any(), any());
  }

  @Test
  void changeOfPriorityThatKeepsTheMergedConfigurationIsIgnored() throws Exception {
    ProfilingRemoteConfigHandler handler = handler();
    RemoteConfigurationStack first = new RemoteConfigurationStack();
    first.push(configKey("a"), 0, config("\"cpuTime\":true"));
    handler.onConfigsChanged(first);

    RemoteConfigurationStack second = new RemoteConfigurationStack();
    second.push(configKey("a"), 3001, config("\"cpuTime\":true"));
    handler.onConfigsChanged(second);

    assertEquals(1, appliedOverrides.size());
    verify(recording, never()).stop();
  }

  @Test
  void noRemoteConfigInitiallyIsIgnored() throws Exception {
    // as when the APM_PROFILING payloads received are not profiling configs, or are dropped
    handlerWhereTheLocalConfigEnablesProfiling().onConfigsChanged(new RemoteConfigurationStack());

    assertFalse(profilingSystem.isRunning());
    verify(controller, never()).reconfigure(any(), any());
    verify(controller, never()).createRecording(any(), any());
  }

  @Test
  void configsThatSetNoFeatureStartTheProfilerWithTheLocalConfig() throws Exception {
    ProfilingRemoteConfigHandler handler = handlerWhereTheLocalConfigEnablesProfiling();
    handler.onConfigsChanged(stackOf(ALL_FEATURES_DISABLED));
    assertFalse(profilingSystem.isRunning());

    handler.onConfigsChanged(stackOfConfigsThatSetNoFeature());

    assertTrue(profilingSystem.isRunning());
    verify(controller).reconfigure(localConfig, emptyMap());
  }

  @Test
  void configsThatSetNoFeatureInitiallyStartTheProfilerWithTheLocalConfig() throws Exception {
    handlerWhereTheLocalConfigEnablesProfiling().onConfigsChanged(stackOfConfigsThatSetNoFeature());

    assertTrue(profilingSystem.isRunning());
    verify(controller).reconfigure(localConfig, emptyMap());
  }

  @Test
  void unchangedConfigsThatSetNoFeatureAreIgnored() throws Exception {
    ProfilingRemoteConfigHandler handler = handlerWhereTheLocalConfigEnablesProfiling();
    handler.onConfigsChanged(stackOfConfigsThatSetNoFeature());

    handler.onConfigsChanged(stackOfConfigsThatSetNoFeature());

    assertTrue(profilingSystem.isRunning());
    verify(recording, never()).stop();
    verify(controller, times(1)).createRecording(any(), any());
  }

  @Test
  void removingConfigsThatSetNoFeatureRevertsToIdle() throws Exception {
    ProfilingRemoteConfigHandler handler = handlerWhereTheLocalConfigEnablesProfiling();
    handler.onConfigsChanged(stackOfConfigsThatSetNoFeature());
    assertTrue(profilingSystem.isRunning());

    handler.onConfigsChanged(new RemoteConfigurationStack());

    assertFalse(profilingSystem.isRunning());
    verify(recording).stop();
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
    assertTrue(appliedOverrides.isEmpty());

    handler.setProfilingSystem(profilingSystem);

    assertTrue(profilingSystem.isRunning());
    assertEquals(1, appliedOverrides.size());
    verify(controller).reconfigure(localConfig, appliedOverrides.get(0));
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
    assertTrue(appliedOverrides.isEmpty());
    verify(controller, never()).reconfigure(any(), any());
  }

  @Test
  void configsAfterProfilerIsSetAreApplied() throws Exception {
    ProfilingRemoteConfigHandler handler = handler(null);
    handler.onConfigsChanged(disabledConfig());
    handler.setProfilingSystem(profilingSystem);

    handler.onConfigsChanged(enabledConfig());

    assertTrue(profilingSystem.isRunning());
    assertEquals(2, appliedOverrides.size(), "the one received early, and this one");
    verify(controller).reconfigure(localConfig, appliedOverrides.get(1));
  }

  @Test
  void profilerStartsAfterTheStartupDelayWhenStartingRightAwayIsNotSafe() throws Exception {
    ProfilingSystem system = systemWithStartupDelay(Duration.ofHours(1));
    handler(system, false).onConfigsChanged(enabledConfig());

    // it is starting, but only records after the startup delay, which isn't a failure to start
    assertTrue(system.isRunning());
    assertFalse(system.isStarted());
    verify(controller).reconfigure(localConfig, appliedOverrides.get(0));
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

    assertEquals(
        2, appliedOverrides.size(), "applied again, although it is the same configuration");
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

  /**
   * Wall clock profiling enabled remotely can only sample nothing with the wall context filter: it
   * then only samples the threads registered while tracing, which are only registered when it was
   * enabled locally. Without the filter, e.g. without tracing, it samples every thread.
   */
  @ParameterizedTest(
      name =
          "{0}: local wall {1}, tracing {2}, context filter {3}, ultra minimal {4}, remote {5} -> {6}")
  @CsvSource({
    "filtered and disabled locally,  false, true,  true,  false, '\"wallTime\":true',  true",
    "enabled locally,                true,  true,  true,  false, '\"wallTime\":true',  false",
    "context filter disabled,        false, true,  false, false, '\"wallTime\":true',  false",
    "tracing disabled,               false, false, true,  false, '\"wallTime\":true',  false",
    "ultra minimal,                  false, true,  true,  true,  '\"wallTime\":true',  false",
    "not enabled remotely,           false, true,  true,  false, '\"wallTime\":false', false",
    "not set remotely,               false, true,  true,  false, '\"cpuTime\":true',   false"
  })
  void wallClockCantSampleOnlyWithTheWallContextFilter(
      String scenario,
      String localWall,
      String tracing,
      String contextFilter,
      String ultraMinimal,
      String remoteProfilers,
      boolean expected) {
    Properties properties = new Properties();
    properties.put(PROFILING_DATADOG_PROFILER_WALL_ENABLED, localWall);
    properties.put(TRACE_ENABLED, tracing);
    properties.put(PROFILING_DATADOG_PROFILER_WALL_CONTEXT_FILTER, contextFilter);
    properties.put(PROFILING_ULTRA_MINIMAL, ultraMinimal);

    // where wall clock profiling is disabled unless opted in, e.g. on J9, the remote enablement is
    // ignored, so there is nothing to warn about
    assertEquals(
        expected && !isJ9(),
        ProfilingRemoteConfigHandler.wallClockCantSample(
            config(remoteProfilers), ConfigProvider.withPropertiesOverride(properties)));
  }

  /** The default local configuration. */
  private static ConfigProvider localConfig() {
    return ConfigProvider.withPropertiesOverride(new Properties());
  }

  private static RemoteConfiguration config(String profilers) {
    return RemoteConfiguration.parse("{\"profilers\":{" + profilers + "}}");
  }

  private static RemoteConfigurationStack stackOf(String profilers) {
    RemoteConfigurationStack stack = new RemoteConfigurationStack();
    stack.push(configKey("config-1"), 0, config(profilers));
    return stack;
  }

  /** Configs that are profiling configs, but set no feature, e.g. {@code {"profilers":{}}}. */
  private static RemoteConfigurationStack stackOfConfigsThatSetNoFeature() {
    RemoteConfigurationStack stack = new RemoteConfigurationStack();
    stack.push(configKey("config-1"), 0, RemoteConfiguration.EMPTY);
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
