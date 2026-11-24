// Copyright 2026 Datadog, Inc.
package datadog.trace.api.profiling;

import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_CONTEXT_FILTER;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_PRECHECK;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_TASKBLOCK_VIRTUAL_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ULTRA_MINIMAL;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_ENABLED;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class TaskBlockInstrumentationConfigTest {

  @Test
  void enabledRequiresEverySharedGate() {
    Config config = config(true);

    assertTrue(
        TaskBlockInstrumentationConfig.isEnabled(
            config, configProvider(true, true, true, false, false)));
    assertFalse(
        TaskBlockInstrumentationConfig.isEnabled(
            config(false), configProvider(true, true, true, false, false)));
    assertFalse(
        TaskBlockInstrumentationConfig.isEnabled(
            config, configProvider(false, true, true, false, false)));
    assertFalse(
        TaskBlockInstrumentationConfig.isEnabled(
            config, configProvider(true, false, true, false, false)));
    assertTrue(
        TaskBlockInstrumentationConfig.isEnabled(
            config, configProvider(true, true, false, false, false)));
    assertFalse(
        TaskBlockInstrumentationConfig.isEnabled(
            config, configProvider(true, true, true, true, false)));
  }

  @Test
  void disabledTracingForcesAllThreadScope() {
    Properties properties = new Properties();
    properties.setProperty(TRACE_ENABLED, "false");
    properties.setProperty(PROFILING_DATADOG_PROFILER_WALL_ENABLED, "true");
    properties.setProperty(PROFILING_DATADOG_PROFILER_WALL_PRECHECK, "true");
    properties.setProperty(PROFILING_DATADOG_PROFILER_WALL_CONTEXT_FILTER, "true");

    assertTrue(
        TaskBlockInstrumentationConfig.isEnabled(
            config(true), ConfigProvider.withPropertiesOverride(properties)));
    assertTrue(TaskBlockInstrumentationConfig.isAllThreadWallScope(configProvider(properties)));
  }

  @Test
  void disabledTracingDisablesWallByDefault() {
    Properties properties = new Properties();
    properties.setProperty(TRACE_ENABLED, "false");
    properties.setProperty(PROFILING_DATADOG_PROFILER_WALL_PRECHECK, "true");

    assertFalse(
        TaskBlockInstrumentationConfig.isEnabled(
            config(true), ConfigProvider.withPropertiesOverride(properties)));
  }

  @Test
  void defaultsDoNotEnableTaskBlockInstrumentation() {
    assertFalse(
        TaskBlockInstrumentationConfig.isEnabled(
            config(true), ConfigProvider.withPropertiesOverride(new Properties())));
    // The wall-clock context filter defaults to true (context-filtered sampling), so the default
    // scope is not all-threads.
    assertFalse(
        TaskBlockInstrumentationConfig.isAllThreadWallScope(
            ConfigProvider.withPropertiesOverride(new Properties())));
  }

  @Test
  void asyncAliasesRemainSupported() {
    Properties properties = new Properties();
    properties.setProperty("profiling.async.wall.enabled", "true");
    properties.setProperty(PROFILING_DATADOG_PROFILER_WALL_PRECHECK, "true");
    properties.setProperty("profiling.async.wall.context.filter", "false");

    assertTrue(
        TaskBlockInstrumentationConfig.isEnabled(
            config(true), ConfigProvider.withPropertiesOverride(properties)));
  }

  @Test
  void virtualParkIsOffByDefaultEvenWhenTaskBlockIsEnabled() {
    Properties properties = enabledProperties();

    assertTrue(TaskBlockInstrumentationConfig.isEnabled(config(true), configProvider(properties)));
    assertFalse(
        TaskBlockInstrumentationConfig.isVirtualParkEnabled(
            config(true), configProvider(properties)));
  }

  @Test
  void virtualParkRequiresTheSubFlagAndEveryTaskBlockGate() {
    Properties properties = enabledProperties();
    properties.setProperty(PROFILING_DATADOG_PROFILER_WALL_TASKBLOCK_VIRTUAL_ENABLED, "true");

    assertTrue(
        TaskBlockInstrumentationConfig.isVirtualParkEnabled(
            config(true), configProvider(properties)));
    assertFalse(
        TaskBlockInstrumentationConfig.isVirtualParkEnabled(
            config(false), configProvider(properties)));
    properties.setProperty(PROFILING_DATADOG_PROFILER_WALL_PRECHECK, "false");
    assertFalse(
        TaskBlockInstrumentationConfig.isVirtualParkEnabled(
            config(true), configProvider(properties)));
  }

  @Test
  void virtualParkSubFlagAloneDoesNotEnableTaskBlock() {
    Properties properties = new Properties();
    properties.setProperty(PROFILING_DATADOG_PROFILER_WALL_TASKBLOCK_VIRTUAL_ENABLED, "true");

    assertFalse(
        TaskBlockInstrumentationConfig.isVirtualParkEnabled(
            config(true), configProvider(properties)));
  }

  @Test
  void virtualParkSubFlagSupportsTheAsyncAlias() {
    Properties properties = enabledProperties();
    properties.setProperty("profiling.async.wall.taskblock.virtual.enabled", "true");

    assertTrue(
        TaskBlockInstrumentationConfig.isVirtualParkEnabled(
            config(true), configProvider(properties)));
  }

  private static Properties enabledProperties() {
    Properties properties = new Properties();
    properties.setProperty(PROFILING_DATADOG_PROFILER_WALL_ENABLED, "true");
    properties.setProperty(PROFILING_DATADOG_PROFILER_WALL_PRECHECK, "true");
    properties.setProperty(PROFILING_DATADOG_PROFILER_WALL_CONTEXT_FILTER, "false");
    return properties;
  }

  private static Config config(boolean ddprofEnabled) {
    Config config = mock(Config.class);
    when(config.isDatadogProfilerEnabled()).thenReturn(ddprofEnabled);
    return config;
  }

  private static ConfigProvider configProvider(
      boolean wallEnabled,
      boolean wallPrecheck,
      boolean tracingEnabled,
      boolean contextFilter,
      boolean ultraMinimal) {
    Properties properties = new Properties();
    properties.setProperty(PROFILING_DATADOG_PROFILER_WALL_ENABLED, Boolean.toString(wallEnabled));
    properties.setProperty(
        PROFILING_DATADOG_PROFILER_WALL_PRECHECK, Boolean.toString(wallPrecheck));
    properties.setProperty(TRACE_ENABLED, Boolean.toString(tracingEnabled));
    properties.setProperty(
        PROFILING_DATADOG_PROFILER_WALL_CONTEXT_FILTER, Boolean.toString(contextFilter));
    properties.setProperty(PROFILING_ULTRA_MINIMAL, Boolean.toString(ultraMinimal));
    return configProvider(properties);
  }

  private static ConfigProvider configProvider(Properties properties) {
    return ConfigProvider.withPropertiesOverride(properties);
  }
}
