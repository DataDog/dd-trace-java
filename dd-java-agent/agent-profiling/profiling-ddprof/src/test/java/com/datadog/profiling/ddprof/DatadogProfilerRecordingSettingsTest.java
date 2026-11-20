package com.datadog.profiling.ddprof;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.profiling.ddprof.DatadogProfiler.RecordingSettings;
import com.datadog.profiling.utils.ProfilingMode;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** Builds start commands from settings only, so it doesn't need the native profiler. */
class DatadogProfilerRecordingSettingsTest {
  private static final Path FILE = Paths.get("/tmp/dd-profiler-test.jfr");

  @Test
  void modesFollowConfig() {
    RecordingSettings cpuOnly = RecordingSettings.from(config(true, false, null, null));
    assertTrue(cpuOnly.profilingModes.contains(ProfilingMode.CPU));
    assertFalse(cpuOnly.profilingModes.contains(ProfilingMode.WALL));

    RecordingSettings wallOnly = RecordingSettings.from(config(false, true, null, null));
    assertFalse(wallOnly.profilingModes.contains(ProfilingMode.CPU));
    assertTrue(wallOnly.profilingModes.contains(ProfilingMode.WALL));
  }

  @Test
  void settingsOfTheLastConfigAreNotComputedAgain() {
    ConfigProvider cpu = config(true, false, null, null);
    ConfigProvider wall = config(false, true, null, null);

    RecordingSettings cpuSettings = RecordingSettings.of(cpu, emptyMap());
    assertSame(cpuSettings, RecordingSettings.of(cpu, emptyMap()));

    RecordingSettings wallSettings = RecordingSettings.of(wall, emptyMap());
    assertTrue(wallSettings.profilingModes.contains(ProfilingMode.WALL));
    assertNotSame(
        cpuSettings, RecordingSettings.of(cpu, emptyMap()), "only the last config is kept");
  }

  @Test
  void commandFollowsSettings() {
    String cpuCommand =
        DatadogProfiler.cmdStartProfiling(
            RecordingSettings.from(config(true, false, "20", null)), emptyList(), FILE);
    assertTrue(cpuCommand.startsWith("start,jfr,file=" + FILE.toAbsolutePath()), cpuCommand);
    assertTrue(cpuCommand.contains(",cpu=20m"), cpuCommand);
    assertFalse(cpuCommand.contains(",wall="), cpuCommand);

    String wallCommand =
        DatadogProfiler.cmdStartProfiling(
            RecordingSettings.from(config(false, true, null, "77")), emptyList(), FILE);
    assertTrue(wallCommand.contains(",wall=~77m"), wallCommand);
    assertFalse(wallCommand.contains(",cpu="), wallCommand);
  }

  @Test
  void contextAttributesArePassedAsGiven() {
    String command =
        DatadogProfiler.cmdStartProfiling(
            RecordingSettings.from(config(true, false, null, null)), asList("foo", "bar"), FILE);
    assertTrue(command.contains(",attributes=foo;bar,"), command);
  }

  @Test
  void profilerOptionsOverrideDerivedOptionsAndAddModes() {
    Properties props = baseProps(true, true);
    props.put(ProfilingConfig.PROFILING_DATADOG_PROFILER_OPTIONS, "cpu=7m,nativemem=131072");
    RecordingSettings settings = settings(props);

    String command = DatadogProfiler.cmdStartProfiling(settings, emptyList(), FILE);

    assertTrue(command.startsWith("start,jfr,file=" + FILE.toAbsolutePath()), command);
    // the explicit cpu option replaces the derived one rather than adding to it
    assertEquals(command.lastIndexOf(",cpu=7m"), command.indexOf(",cpu="), command);
    assertTrue(command.contains(",wall="), command);
    assertTrue(command.endsWith(",cpu=7m,nativemem=131072"), command);
    // nativemem only comes from the options: in effect, but not derived from the flags
    assertTrue(settings.profilingModes.contains(ProfilingMode.NATIVEMEM));
    assertFalse(settings.configuredProfilingModes.contains(ProfilingMode.NATIVEMEM));
  }

  @Test
  void eventOptionReplacesDerivedCpuOptionButKeepsCpuMode() {
    RecordingSettings settings = settings(withOptions(baseProps(true, false), "event=cycles"));

    String command = DatadogProfiler.cmdStartProfiling(settings, emptyList(), FILE);

    assertFalse(command.contains(",cpu="), command);
    assertTrue(command.endsWith(",event=cycles"), command);
    assertTrue(settings.profilingModes.contains(ProfilingMode.CPU));
  }

  @Test
  void noopEventDisablesCpuModeInEffectOnly() {
    Properties props = baseProps(true, false);
    props.put(ProfilingConfig.PROFILING_DATADOG_PROFILER_SCHEDULING_EVENT, "cycles");
    RecordingSettings settings = settings(withOptions(props, "event=noop"));

    String command = DatadogProfiler.cmdStartProfiling(settings, emptyList(), FILE);

    assertFalse(command.contains(",event=cycles"), command);
    assertTrue(command.endsWith(",event=noop"), command);
    // cpu is still enabled by the flags, which is what shapes the derived command, but no
    // longer in effect
    assertTrue(settings.configuredProfilingModes.contains(ProfilingMode.CPU));
    assertFalse(settings.profilingModes.contains(ProfilingMode.CPU));
  }

  @Test
  void modesImpliedByProfilerOptionsCountAsEnabled() {
    Properties none = baseProps(false, false);
    assertFalse(DatadogProfiler.isAnyProfilingModeEnabled(config(none)));

    // nothing is enabled by the flags, but the options turn wall on
    assertTrue(DatadogProfiler.isAnyProfilingModeEnabled(config(withOptions(none, "wall=50m"))));
  }

  @Test
  void settingsWithoutProfilerOptionsAreUnaffected() {
    RecordingSettings settings = settings(baseProps(true, false));

    assertEquals(settings.configuredProfilingModes, settings.profilingModes);
    assertFalse(
        DatadogProfiler.cmdStartProfiling(settings, emptyList(), FILE).contains(",nativemem="));
  }

  @Test
  void modeOverridesSwitchModesOverTheConfig() {
    Map<ProfilingMode, Boolean> overrides = new EnumMap<>(ProfilingMode.class);
    overrides.put(ProfilingMode.CPU, false);
    overrides.put(ProfilingMode.WALL, true);

    RecordingSettings settings = RecordingSettings.from(config(baseProps(true, false)), overrides);
    String command = DatadogProfiler.cmdStartProfiling(settings, emptyList(), FILE);

    assertFalse(settings.configuredProfilingModes.contains(ProfilingMode.CPU));
    assertTrue(settings.configuredProfilingModes.contains(ProfilingMode.WALL));
    assertFalse(command.contains(",cpu="), command);
    assertTrue(command.contains(",wall="), command);
  }

  @Test
  void settingsAreComputedAgainForOtherModeOverrides() {
    ConfigProvider config = config(baseProps(true, false));

    RecordingSettings local = RecordingSettings.of(config, emptyMap());
    RecordingSettings overridden =
        RecordingSettings.of(config, singletonMap(ProfilingMode.CPU, false));

    assertNotSame(local, overridden);
    assertFalse(overridden.profilingModes.contains(ProfilingMode.CPU));
    assertSame(overridden, RecordingSettings.of(config, singletonMap(ProfilingMode.CPU, false)));
  }

  @Test
  void liveHeapSizeFollowsLiveHeapProfilingUnlessOverridden() {
    RecordingSettings settings =
        RecordingSettings.from(
            config(baseProps(false, false)), singletonMap(ProfilingMode.MEMLEAK, true));

    // the size of the live heap is tracked by default
    assertTrue(settings.configuredProfilingModes.contains(ProfilingMode.LIVEHEAP_SIZE));
    String command = DatadogProfiler.cmdStartProfiling(settings, emptyList(), FILE);
    assertTrue(command.contains("b:L:"), command);
  }

  @Test
  void liveHeapSizeTrackingFollowsTheConfigUnlessOverridden() {
    Properties props = baseProps(false, false);
    props.put(ProfilingConfig.PROFILING_DATADOG_PROFILER_LIVEHEAP_TRACK_HEAPSIZE, "false");

    RecordingSettings settings =
        RecordingSettings.from(config(props), singletonMap(ProfilingMode.MEMLEAK, true));

    assertFalse(settings.configuredProfilingModes.contains(ProfilingMode.LIVEHEAP_SIZE));
    String command = DatadogProfiler.cmdStartProfiling(settings, emptyList(), FILE);
    assertTrue(command.contains("b:l:"), command);
  }

  @Test
  void liveHeapSizeSwitchedOffKeepsLiveHeapProfilingWithoutTheSize() {
    Map<ProfilingMode, Boolean> overrides = new EnumMap<>(ProfilingMode.class);
    overrides.put(ProfilingMode.MEMLEAK, true);
    overrides.put(ProfilingMode.LIVEHEAP_SIZE, false);

    RecordingSettings settings = RecordingSettings.from(config(baseProps(false, false)), overrides);

    String command = DatadogProfiler.cmdStartProfiling(settings, emptyList(), FILE);
    assertTrue(command.contains("b:l:"), command);
  }

  @Test
  void liveHeapSizeSwitchedOnEnablesLiveHeapProfiling() {
    Map<ProfilingMode, Boolean> overrides = new EnumMap<>(ProfilingMode.class);
    overrides.put(ProfilingMode.MEMLEAK, false);
    overrides.put(ProfilingMode.LIVEHEAP_SIZE, true);

    RecordingSettings settings = RecordingSettings.from(config(baseProps(false, false)), overrides);

    // the profiler has no size-only mode: the live heap objects are sampled too
    assertFalse(settings.configuredProfilingModes.contains(ProfilingMode.MEMLEAK));
    String command = DatadogProfiler.cmdStartProfiling(settings, emptyList(), FILE);
    assertTrue(command.contains("b:L:"), command);
    assertTrue(
        DatadogProfiler.isAnyProfilingModeEnabled(config(baseProps(false, false)), overrides));
  }

  @Test
  void liveHeapSwitchedOffDoesNotTrackItsSize() {
    RecordingSettings settings =
        RecordingSettings.from(
            config(baseProps(false, false)), singletonMap(ProfilingMode.MEMLEAK, false));

    assertFalse(settings.configuredProfilingModes.contains(ProfilingMode.LIVEHEAP_SIZE));
    assertFalse(
        DatadogProfiler.cmdStartProfiling(settings, emptyList(), FILE).contains(",memory="));
  }

  @Test
  void liveHeapSizeProfilerOptionEnablesTheMode() {
    RecordingSettings settings =
        settings(withOptions(baseProps(false, false), "memory=524288b:L:0.10"));

    assertTrue(settings.profilingModes.contains(ProfilingMode.MEMLEAK));
    assertTrue(settings.profilingModes.contains(ProfilingMode.LIVEHEAP_SIZE));
  }

  @Test
  void liveHeapProfilerOptionWithoutSizeDoesNotEnableTheMode() {
    RecordingSettings settings =
        settings(withOptions(baseProps(false, false), "memory=524288b:l:0.10"));

    assertTrue(settings.profilingModes.contains(ProfilingMode.MEMLEAK));
    assertFalse(settings.profilingModes.contains(ProfilingMode.LIVEHEAP_SIZE));
  }

  /** Flags that leave only cpu and/or wall potentially enabled, whatever the JVM. */
  private static Properties baseProps(boolean cpu, boolean wall) {
    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_DATADOG_PROFILER_CPU_ENABLED, Boolean.toString(cpu));
    props.put(ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_ENABLED, Boolean.toString(wall));
    props.put(ProfilingConfig.PROFILING_ALLOCATION_ENABLED, "false");
    props.put(ProfilingConfig.PROFILING_HEAP_ENABLED, "false");
    return props;
  }

  private static Properties withOptions(Properties props, String options) {
    props.put(ProfilingConfig.PROFILING_DATADOG_PROFILER_OPTIONS, options);
    return props;
  }

  private static ConfigProvider config(Properties props) {
    return ConfigProvider.withPropertiesOverride(props);
  }

  private static RecordingSettings settings(Properties props) {
    return RecordingSettings.from(config(props));
  }

  private static ConfigProvider config(
      boolean cpu, boolean wall, String cpuIntervalMs, String wallIntervalMs) {
    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_DATADOG_PROFILER_CPU_ENABLED, Boolean.toString(cpu));
    props.put(ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_ENABLED, Boolean.toString(wall));
    if (cpuIntervalMs != null) {
      props.put(ProfilingConfig.PROFILING_DATADOG_PROFILER_CPU_INTERVAL, cpuIntervalMs);
    }
    if (wallIntervalMs != null) {
      props.put(ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_INTERVAL, wallIntervalMs);
    }
    return ConfigProvider.withPropertiesOverride(props);
  }
}
