package com.datadog.profiling.ddprof;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

  // --- Umbrella property tests ---

  @Test
  void testCpuUmbrellaFalseDisablesCpu() {
    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_CPU_ENABLED, "false");
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);
    assertFalse(DatadogProfilerConfig.isCpuProfilerEnabled(configProvider));
  }

  @Test
  void testCpuUmbrellaTrueEnablesCpu() {
    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_CPU_ENABLED, "true");
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);
    assertTrue(DatadogProfilerConfig.isCpuProfilerEnabled(configProvider));
  }

  @Test
  void testDdprofCpuOverridesTrueWinsOverUmbrellaFalse() {
    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_CPU_ENABLED, "false");
    props.put(ProfilingConfig.PROFILING_DATADOG_PROFILER_CPU_ENABLED, "true");
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);
    assertTrue(
        DatadogProfilerConfig.isCpuProfilerEnabled(configProvider),
        "profiling.ddprof.cpu.enabled=true should override profiling.cpu.enabled=false");
  }

  @Test
  void testLatencyUmbrellaFalseDisablesWall() {
    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_LATENCY_ENABLED, "false");
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);
    assertFalse(DatadogProfilerConfig.isWallClockProfilerEnabled(configProvider));
  }

  @Test
  void testLatencyUmbrellaTrueEnablesWall() {
    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_LATENCY_ENABLED, "true");
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);
    assertTrue(DatadogProfilerConfig.isWallClockProfilerEnabled(configProvider));
  }

  @Test
  void testDdprofWallOverridesFalseWinsOverLatencyUmbrellaTrue() {
    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_LATENCY_ENABLED, "true");
    props.put(ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_ENABLED, "false");
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);
    assertFalse(
        DatadogProfilerConfig.isWallClockProfilerEnabled(configProvider),
        "profiling.ddprof.wall.enabled=false should override profiling.latency.enabled=true");
  }

  @Test
  void testLiveheapUmbrellaFalseDisablesDdprofLiveheap() {
    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_LIVEHEAP_ENABLED, "false");
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);
    assertFalse(DatadogProfilerConfig.isMemoryLeakProfilingEnabled(configProvider));
  }

  @Test
  void testLiveheapUmbrellaTrueEnablesDdprofLiveheap() {
    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_LIVEHEAP_ENABLED, "true");
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);
    assertTrue(DatadogProfilerConfig.isMemoryLeakProfilingEnabled(configProvider));
  }

  @Test
  void testDdprofLiveheapFalseOverridesUmbrellaTrue() {
    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_LIVEHEAP_ENABLED, "true");
    props.put(ProfilingConfig.PROFILING_DATADOG_PROFILER_LIVEHEAP_ENABLED, "false");
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);
    assertFalse(
        DatadogProfilerConfig.isMemoryLeakProfilingEnabled(configProvider),
        "profiling.ddprof.liveheap.enabled=false should override profiling.liveheap.enabled=true");
  }

  @Test
  void testAllocUmbrellaTrueEnablesDdprofAlloc() {
    assumeTrue(JavaVirtualMachine.isJavaVersionAtLeast(11));
    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_ALLOC_ENABLED, "true");
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);
    assertTrue(DatadogProfilerConfig.isAllocationProfilingEnabled(configProvider));
  }

  @Test
  void testAllocUmbrellaFalseDisablesDdprofAlloc() {
    assumeTrue(JavaVirtualMachine.isJavaVersionAtLeast(11));
    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_ALLOC_ENABLED, "false");
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);
    assertFalse(DatadogProfilerConfig.isAllocationProfilingEnabled(configProvider));
  }

  @Test
  void testDdprofAllocTrueOverridesAllocUmbrellaFalse() {
    assumeTrue(JavaVirtualMachine.isJavaVersionAtLeast(11));
    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_ALLOC_ENABLED, "false");
    props.put(ProfilingConfig.PROFILING_DATADOG_PROFILER_ALLOC_ENABLED, "true");
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);
    assertTrue(
        DatadogProfilerConfig.isAllocationProfilingEnabled(configProvider),
        "profiling.ddprof.alloc.enabled=true should override profiling.alloc.enabled=false");
  }

  @Test
  void testDdprofWallTrueBypassesTracingGuard() {
    Properties props = new Properties();
    props.put("trace.enabled", "false");
    props.put(ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_ENABLED, "true");
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);
    assertTrue(
        DatadogProfilerConfig.isWallClockProfilerEnabled(configProvider),
        "profiling.ddprof.wall.enabled=true should bypass the tracing-disabled guard");
  }

  @Test
  void testDdprofWallTrueBypassesUltraMinimalGuard() {
    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_ULTRA_MINIMAL, "true");
    props.put(ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_ENABLED, "true");
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);
    assertTrue(
        DatadogProfilerConfig.isWallClockProfilerEnabled(configProvider),
        "profiling.ddprof.wall.enabled=true should bypass the ultra-minimal guard");
  }

  @Test
  void testLatencyUmbrellaTrueWithTracingDisabledDoesNotEnableWall() {
    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_LATENCY_ENABLED, "true");
    props.put("trace.enabled", "false");
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);
    assertFalse(
        DatadogProfilerConfig.isWallClockProfilerEnabled(configProvider),
        "profiling.latency.enabled=true should not enable wall-clock when tracing is disabled");
  }

  @Test
  void testDefaultsPreservedWhenNoUmbrellaSet() {
    // Verify that with no umbrella properties set, existing defaults are preserved
    Properties props = new Properties();
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    // CPU default: true
    assertTrue(
        DatadogProfilerConfig.isCpuProfilerEnabled(configProvider),
        "CPU profiling should be enabled by default when no umbrella set");

    // Wall default: depends on tracing/J9/ultra-minimal, but on HotSpot with tracing=true it's ON
    assertTrue(
        DatadogProfilerConfig.isWallClockProfilerEnabled(configProvider),
        "Wall-clock profiling should be enabled by default on HotSpot with tracing");

    // Liveheap default: true (all GA features enabled by default)
    assertTrue(
        DatadogProfilerConfig.isMemoryLeakProfilingEnabled(configProvider),
        "Liveheap profiling should be enabled by default");
  }

  @Test
  void testOldAllocationUmbrellaFalseDisablesDdprofAlloc() {
    assumeTrue(JavaVirtualMachine.isJavaVersionAtLeast(11));
    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_ALLOCATION_ENABLED, "false");
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);
    assertFalse(DatadogProfilerConfig.isAllocationProfilingEnabled(configProvider));
  }
}
