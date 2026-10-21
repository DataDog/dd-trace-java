package com.datadog.profiling.ddprof;

import static com.datadog.profiling.controller.ProfilingSupport.isOldObjectSampleAvailable;
import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import datadog.environment.JavaVirtualMachine;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;

class DatadogProfilerConfigTest {
  private String originalVmName;

  @BeforeEach
  void setUp() {
    originalVmName = System.getProperty("java.vm.name");
  }

  @AfterEach
  void tearDown() {
    if (originalVmName != null) {
      System.setProperty("java.vm.name", originalVmName);
    } else {
      System.clearProperty("java.vm.name");
    }
  }

  @ParameterizedTest
  @MethodSource("j9StackwalkerTestCases")
  void testGetCStackWithJ9(String configuredStackwalker, String expectedStackwalker) {
    System.setProperty("java.vm.name", "Eclipse OpenJ9 VM");

    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_DATADOG_PROFILER_CSTACK, configuredStackwalker);
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    String result = DatadogProfilerConfig.getCStack(configProvider);

    assertEquals(
        expectedStackwalker,
        result,
        "J9 JVM with configured stackwalker '"
            + configuredStackwalker
            + "' should return '"
            + expectedStackwalker
            + "'");
  }

  @ParameterizedTest
  @MethodSource("zingStackwalkerTestCases")
  void testGetCStackWithZing(String configuredStackwalker, String expectedStackwalker) {
    System.setProperty("java.vm.name", "Zing 64-Bit Tiered VM");

    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_DATADOG_PROFILER_CSTACK, configuredStackwalker);
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    String result = DatadogProfilerConfig.getCStack(configProvider);

    assertEquals(
        expectedStackwalker,
        result,
        "Zing JVM with configured stackwalker '"
            + configuredStackwalker
            + "' should return '"
            + expectedStackwalker
            + "'");
  }

  @ParameterizedTest
  @MethodSource("hotspotStackwalkerTestCases")
  void testGetCStackWithHotspot(String configuredStackwalker, String expectedStackwalker) {
    System.setProperty("java.vm.name", "Java HotSpot(TM) 64-Bit Server VM");

    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_DATADOG_PROFILER_CSTACK, configuredStackwalker);
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    String result = DatadogProfilerConfig.getCStack(configProvider);

    assertEquals(
        expectedStackwalker,
        result,
        "HotSpot JVM with configured stackwalker '"
            + configuredStackwalker
            + "' should return '"
            + expectedStackwalker
            + "'");
  }

  @Test
  void profilerOptionsReadDdprofKey() {
    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_DATADOG_PROFILER_OPTIONS, "cpu=10m");

    assertEquals(
        "cpu=10m",
        DatadogProfilerConfig.getProfilerOptions(ConfigProvider.withPropertiesOverride(props)));
  }

  @Test
  void profilerOptionsAreNullWhenUnset() {
    assertNull(
        DatadogProfilerConfig.getProfilerOptions(
            ConfigProvider.withPropertiesOverride(new Properties())));
  }

  @ParameterizedTest
  @MethodSource("profilerOptionMergeCases")
  void mergeProfilerOptions(
      String derivedOptions, String configuredOptions, String expectedOptions) {
    assertEquals(
        expectedOptions, DatadogProfiler.mergeProfilerOptions(derivedOptions, configuredOptions));
  }

  @Test
  void warningIsNotEmittedWithoutProfilerOptions() {
    Logger logger = mock(Logger.class);

    DatadogProfiler.warnIfProfilerOptionsConfigured(logger, null);
    DatadogProfiler.warnIfProfilerOptionsConfigured(logger, "");

    verifyNoInteractions(logger);
  }

  @Test
  void warningIsMarkedForTelemetryWhenProfilerOptionsAreConfigured() {
    Logger logger = mock(Logger.class);

    DatadogProfiler.warnIfProfilerOptionsConfigured(logger, "cpu=7m");

    verify(logger).warn(SEND_TELEMETRY, DatadogProfiler.PROFILER_OPTIONS_WARNING);
  }

  private static Stream<Arguments> profilerOptionMergeCases() {
    return Stream.of(
        Arguments.of(
            ",cpu=10m,wall=50m,loglevel=INFO",
            "cpu=7m,nativemem=131072",
            ",wall=50m,loglevel=INFO,cpu=7m,nativemem=131072"),
        Arguments.of(
            ",loglevel=INFO,cpu=10m,loglevel=INFO", "loglevel=DEBUG", ",cpu=10m,loglevel=DEBUG"),
        Arguments.of(",natsock=100us,wall=50m", "natsock", ",wall=50m,natsock"),
        Arguments.of(
            ",cpu=10m,wall=50m",
            " , cpu=7m, , nativemem=131072, ",
            ",wall=50m,cpu=7m,nativemem=131072"),
        Arguments.of(",cpu=10m,wall=50m", "  ", ",cpu=10m,wall=50m"),
        Arguments.of(",cpu=10m,wall=50m", "cpu=5m,cpu=7m", ",wall=50m,cpu=7m"),
        Arguments.of(",cpu=10m", "start,jfr,file=other.jfr,cpu=7m", ",cpu=7m"));
  }

  private static Stream<Arguments> j9StackwalkerTestCases() {
    return Stream.of(
        // Unsupported stackwalkers - should fall back to dwarf
        Arguments.of("vm", "dwarf"),
        Arguments.of("vmx", "dwarf"),
        // Supported stackwalkers - should pass through
        Arguments.of("dwarf", "dwarf"),
        Arguments.of("fp", "fp"),
        Arguments.of("lbr", "lbr"),
        Arguments.of("no", "no"));
  }

  private static Stream<Arguments> zingStackwalkerTestCases() {
    return Stream.of(
        // Unsupported stackwalkers - should fall back to dwarf
        Arguments.of("vm", "dwarf"),
        Arguments.of("vmx", "dwarf"),
        // Supported stackwalkers - should pass through
        Arguments.of("dwarf", "dwarf"),
        Arguments.of("fp", "fp"),
        Arguments.of("lbr", "lbr"),
        Arguments.of("no", "no"));
  }

  private static Stream<Arguments> hotspotStackwalkerTestCases() {
    return Stream.of(
        // All stackwalkers should pass through unchanged
        Arguments.of("vm", "vm"),
        Arguments.of("vmx", "vmx"),
        Arguments.of("dwarf", "dwarf"),
        Arguments.of("fp", "fp"),
        Arguments.of("lbr", "lbr"),
        Arguments.of("no", "no"));
  }

  @Test
  void isMemoryLeakProfilingSafeConsistentWithComponentChecks() {
    boolean expected = DatadogProfilerConfig.isJmethodIDSafe() || isOldObjectSampleAvailable();
    assertEquals(expected, DatadogProfilerConfig.isMemoryLeakProfilingSafe());
  }

  @Test
  void unifiedFlagEnabledDdprofKeyDefaultReflectsSafety() {
    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_HEAP_ENABLED, "true");
    ConfigProvider config = ConfigProvider.withPropertiesOverride(props);
    // ddprof live heap requires Java 11+ (JVMTI Allocation Sampler) AND jmethodID safety
    boolean expectedDefault =
        JavaVirtualMachine.isJavaVersionAtLeast(11) && DatadogProfilerConfig.isJmethodIDSafe();
    assertEquals(expectedDefault, DatadogProfilerConfig.isMemoryLeakProfilingEnabled(config));
  }

  @Test
  void unifiedFlagDisabledOverridesDdprof() {
    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_HEAP_ENABLED, "false");
    props.put(ProfilingConfig.PROFILING_DATADOG_PROFILER_LIVEHEAP_ENABLED, "true");
    ConfigProvider config = ConfigProvider.withPropertiesOverride(props);
    assertFalse(DatadogProfilerConfig.isMemoryLeakProfilingEnabled(config));
  }

  @Test
  void unifiedFlagEnabledDdprofKeyDisabledReturnsFalse() {
    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_HEAP_ENABLED, "true");
    props.put(ProfilingConfig.PROFILING_DATADOG_PROFILER_LIVEHEAP_ENABLED, "false");
    ConfigProvider config = ConfigProvider.withPropertiesOverride(props);
    assertFalse(DatadogProfilerConfig.isMemoryLeakProfilingEnabled(config));
  }

  @Test
  void unifiedFlagEnabledDdprofKeyEnabledReturnsTrue() {
    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_HEAP_ENABLED, "true");
    props.put(ProfilingConfig.PROFILING_DATADOG_PROFILER_LIVEHEAP_ENABLED, "true");
    ConfigProvider config = ConfigProvider.withPropertiesOverride(props);
    assertTrue(DatadogProfilerConfig.isMemoryLeakProfilingEnabled(config));
  }

  @Test
  void oldMemleakAliasStillWorks() {
    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_HEAP_ENABLED, "true");
    props.put(ProfilingConfig.PROFILING_DATADOG_PROFILER_MEMLEAK_ENABLED, "true");
    ConfigProvider config = ConfigProvider.withPropertiesOverride(props);
    assertTrue(DatadogProfilerConfig.isMemoryLeakProfilingEnabled(config));
  }

  @Test
  void defaultBehaviorNoFlagsSetUsesAutoDetection() {
    Properties props = new Properties();
    ConfigProvider config = ConfigProvider.withPropertiesOverride(props);
    // With no flags set:
    // unified default = isMemoryLeakProfilingSafe()
    // ddprof default = Java 11+ && isJmethodIDSafe()
    // result = isMemoryLeakProfilingSafe() && (Java 11+ && isJmethodIDSafe())
    boolean expectedDefault =
        JavaVirtualMachine.isJavaVersionAtLeast(11) && DatadogProfilerConfig.isJmethodIDSafe();
    assertEquals(expectedDefault, DatadogProfilerConfig.isMemoryLeakProfilingEnabled(config));
  }
}
