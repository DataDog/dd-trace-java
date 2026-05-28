package datadog.trace.api.profiling;

import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_PRECHECK;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DELEGATE_MONITOR_EVENTS_TO_AGENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.util.Collections;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TaskBlockInstrumentationConfigTest {

  @ParameterizedTest
  @MethodSource("monitorDelegationModes")
  void shouldUseJavaMonitorTaskBlockInstrumentationIsAllOrNative(
      boolean requested,
      boolean wallPrecheck,
      boolean integrationsEnabled,
      boolean objectWaitEnabled,
      boolean synchronizedContentionEnabled,
      boolean expected) {
    ConfigProvider configProvider = configProvider(requested, wallPrecheck);
    InstrumenterConfig instrumenterConfig =
        instrumenterConfig(integrationsEnabled, objectWaitEnabled, synchronizedContentionEnabled);

    assertEquals(
        expected,
        TaskBlockInstrumentationConfig.shouldUseJavaMonitorTaskBlockInstrumentation(
            configProvider, instrumenterConfig));
  }

  private static Stream<Arguments> monitorDelegationModes() {
    return Stream.of(
        Arguments.of(true, true, true, true, true, true),
        Arguments.of(true, true, true, true, false, false),
        Arguments.of(true, true, true, false, true, false),
        Arguments.of(false, true, true, true, true, false),
        Arguments.of(true, false, true, true, true, false),
        Arguments.of(true, true, false, true, true, false));
  }

  private static ConfigProvider configProvider(boolean requested, boolean wallPrecheck) {
    Properties properties = new Properties();
    properties.setProperty(PROFILING_DELEGATE_MONITOR_EVENTS_TO_AGENT, Boolean.toString(requested));
    properties.setProperty(
        PROFILING_DATADOG_PROFILER_WALL_PRECHECK, Boolean.toString(wallPrecheck));
    return ConfigProvider.withPropertiesOverride(properties);
  }

  private static InstrumenterConfig instrumenterConfig(
      boolean integrationsEnabled,
      boolean objectWaitEnabled,
      boolean synchronizedContentionEnabled) {
    InstrumenterConfig instrumenterConfig = mock(InstrumenterConfig.class);
    when(instrumenterConfig.isIntegrationsEnabled()).thenReturn(integrationsEnabled);
    when(instrumenterConfig.isIntegrationEnabled(
            eq(Collections.singletonList("object-wait")), eq(true)))
        .thenReturn(objectWaitEnabled);
    when(instrumenterConfig.isIntegrationEnabled(
            eq(Collections.singletonList("synchronized-contention")), eq(true)))
        .thenReturn(synchronizedContentionEnabled);
    return instrumenterConfig;
  }
}
