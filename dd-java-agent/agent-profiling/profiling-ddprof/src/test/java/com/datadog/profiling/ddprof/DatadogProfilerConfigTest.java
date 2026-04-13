package com.datadog.profiling.ddprof;

import static com.datadog.profiling.controller.ProfilingSupport.isOldObjectSampleAvailable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
