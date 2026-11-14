package com.datadog.profiling.remoteconfig;

import static com.datadog.profiling.remoteconfig.RemoteConfiguration.Feature.ALLOCATIONS;
import static com.datadog.profiling.remoteconfig.RemoteConfiguration.Feature.CPU_TIME;
import static com.datadog.profiling.remoteconfig.RemoteConfiguration.Feature.HEAP_OBJECTS;
import static com.datadog.profiling.remoteconfig.RemoteConfiguration.Feature.WALL_TIME;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ALLOCATION_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_CPU_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_LIVEHEAP_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_HEAP_ENABLED;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.profiling.remoteconfig.RemoteConfiguration.Feature;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class RemoteConfigurationTest {

  @Test
  void parseReadsEveryFeature() {
    RemoteConfiguration configuration =
        RemoteConfiguration.parse(
            "{\"profilers\":{\"cpuTime\":true,\"wallTime\":false,"
                + "\"heapObjects\":false,\"allocations\":true},\"targets\":[]}");

    assertEquals(true, configuration.get(CPU_TIME));
    assertEquals(false, configuration.get(WALL_TIME));
    assertEquals(false, configuration.get(HEAP_OBJECTS));
    assertEquals(true, configuration.get(ALLOCATIONS));
  }

  @Test
  void parseLeavesTheOtherFeaturesUnset() {
    RemoteConfiguration configuration =
        RemoteConfiguration.parse("{\"profilers\":{\"cpuTime\":true}}");

    assertEquals(true, configuration.get(CPU_TIME));
    assertNull(configuration.get(WALL_TIME));
    assertNull(configuration.get(HEAP_OBJECTS));
    assertNull(configuration.get(ALLOCATIONS));
  }

  @Test
  void parseIgnoresUnknownAndInvalidFeatures() {
    RemoteConfiguration configuration =
        RemoteConfiguration.parse(
            "{\"profilers\":{\"cpuTime\":\"true\",\"wallTime\":1,\"heapSize\":true,"
                + "\"heapObjects\":{},\"allocations\":false,\"locks\":true}}");

    // only the valid one is set
    Map<Feature, Boolean> expected = new EnumMap<>(Feature.class);
    expected.put(ALLOCATIONS, false);
    assertEquals(RemoteConfiguration.of(expected), configuration);
  }

  @TableTest({
    "Scenario                | Json                  ",
    "no profilers            | '{\"targets\":[{}]}'  ",
    "profilers not an object | '{\"profilers\":[]}'  ",
    "profilers null          | '{\"profilers\":null}'",
    "empty profilers         | '{\"profilers\":{}}'  ",
    "not an object           | '[]'                  ",
    "invalid json            | '{\"profilers\":{'    "
  })
  void parseWithoutFeaturesGivesAnEmptyConfiguration(String json) {
    assertSame(RemoteConfiguration.EMPTY, RemoteConfiguration.parse(json));
  }

  @Test
  void parseFindsProfilersAfterTheOtherSections() {
    RemoteConfiguration configuration =
        RemoteConfiguration.parse(
            "{\"targets\":[{\"service\":\"x\"}],\"settings\":{\"a\":1},\"profilers\":{\"cpuTime\":true}}");

    assertEquals(true, configuration.get(CPU_TIME));
  }

  @Test
  void overriddenByTakesTheFeaturesTheHigherConfigurationSets() {
    RemoteConfiguration lower =
        RemoteConfiguration.parse("{\"profilers\":{\"cpuTime\":false,\"wallTime\":true}}");
    RemoteConfiguration higher =
        RemoteConfiguration.parse("{\"profilers\":{\"cpuTime\":true,\"allocations\":true}}");

    RemoteConfiguration merged = lower.overriddenBy(higher);

    assertEquals(true, merged.get(CPU_TIME), "overwritten by the higher one");
    assertEquals(true, merged.get(WALL_TIME), "only set by the lower one");
    assertEquals(true, merged.get(ALLOCATIONS), "only set by the higher one");
    assertNull(merged.get(HEAP_OBJECTS));
    // the original configurations don't change
    assertEquals(false, lower.get(CPU_TIME));
    assertNull(higher.get(WALL_TIME));
  }

  @Test
  void overriddenByAnEmptyConfigurationChangesNothing() {
    RemoteConfiguration configuration =
        RemoteConfiguration.parse("{\"profilers\":{\"cpuTime\":true}}");

    assertSame(configuration, configuration.overriddenBy(RemoteConfiguration.EMPTY));
    assertSame(configuration, RemoteConfiguration.EMPTY.overriddenBy(configuration));
  }

  @Test
  void emptyConfigurationSetsNothing() {
    assertTrue(RemoteConfiguration.EMPTY.isEmpty());
    assertFalse(RemoteConfiguration.parse("{\"profilers\":{\"cpuTime\":false}}").isEmpty());
    assertEquals(emptyMap(), RemoteConfiguration.EMPTY.toConfigValues());
  }

  @Test
  void ofWithoutFeaturesIsTheEmptyConfiguration() {
    assertSame(RemoteConfiguration.EMPTY, RemoteConfiguration.of(new HashMap<>()));
  }

  @Test
  void cpuWallAndAllocationsMapToTheirOwnSettings() {
    Map<String, String> expected = new HashMap<>();
    expected.put(PROFILING_DATADOG_PROFILER_CPU_ENABLED, "true");
    expected.put(PROFILING_DATADOG_PROFILER_WALL_ENABLED, "false");
    expected.put(PROFILING_ALLOCATION_ENABLED, "true");

    assertEquals(
        expected,
        RemoteConfiguration.parse(
                "{\"profilers\":{\"cpuTime\":true,\"wallTime\":false,\"allocations\":true}}")
            .toConfigValues());
  }

  @Test
  void heapObjectsEnableTheLiveHeap() {
    Map<String, String> expected = new HashMap<>();
    expected.put(PROFILING_HEAP_ENABLED, "true");
    expected.put(PROFILING_DATADOG_PROFILER_LIVEHEAP_ENABLED, "true");

    assertEquals(
        expected,
        RemoteConfiguration.parse("{\"profilers\":{\"heapObjects\":true}}").toConfigValues());
  }

  @Test
  void heapObjectsDisabledTurnsTheLiveHeapOff() {
    Map<String, String> expected = new HashMap<>();
    expected.put(PROFILING_HEAP_ENABLED, "false");
    expected.put(PROFILING_DATADOG_PROFILER_LIVEHEAP_ENABLED, "false");

    assertEquals(
        expected,
        RemoteConfiguration.parse("{\"profilers\":{\"heapObjects\":false}}").toConfigValues());
  }

  @Test
  void heapSizeIsNotSupportedAndIgnored() {
    assertSame(
        RemoteConfiguration.EMPTY,
        RemoteConfiguration.parse("{\"profilers\":{\"heapSize\":true}}"));
  }

  @Test
  void featuresNotSetAreNotInTheConfigValues() {
    Map<String, String> values =
        RemoteConfiguration.parse("{\"profilers\":{\"cpuTime\":true}}").toConfigValues();

    assertEquals(1, values.size());
    assertFalse(values.containsKey(PROFILING_DATADOG_PROFILER_WALL_ENABLED));
    assertFalse(values.containsKey(PROFILING_HEAP_ENABLED));
  }

  @Test
  void equalConfigurationsAreEqual() {
    RemoteConfiguration a = RemoteConfiguration.parse("{\"profilers\":{\"cpuTime\":true}}");
    RemoteConfiguration b = RemoteConfiguration.parse("{\"profilers\":{\"cpuTime\":true},\"x\":1}");
    RemoteConfiguration c = RemoteConfiguration.parse("{\"profilers\":{\"cpuTime\":false}}");

    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
    assertNotEquals(a, c);
  }

  @Test
  void configurationIsNotAffectedByLaterChangesToTheMapItWasBuiltFrom() {
    Map<Feature, Boolean> values = new EnumMap<>(Feature.class);
    values.put(CPU_TIME, true);
    RemoteConfiguration configuration = RemoteConfiguration.of(values);

    values.put(WALL_TIME, true);

    assertNull(configuration.get(WALL_TIME));
  }
}
