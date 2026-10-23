package com.datadog.profiling.ddprof;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.profiling.ddprof.DatadogProfiler.RecordingSettings;
import com.datadog.profiling.utils.ProfilingMode;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.nio.file.Path;
import java.nio.file.Paths;
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
