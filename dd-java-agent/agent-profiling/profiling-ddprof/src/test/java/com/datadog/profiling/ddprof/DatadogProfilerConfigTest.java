package com.datadog.profiling.ddprof;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
}
