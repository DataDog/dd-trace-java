package com.datadog.profiling.remoteconfig;

import static com.datadog.profiling.remoteconfig.RemoteConfiguration.Feature.ALLOCATIONS;
import static com.datadog.profiling.remoteconfig.RemoteConfiguration.Feature.CPU_TIME;
import static com.datadog.profiling.remoteconfig.RemoteConfiguration.Feature.HEAP_OBJECTS;
import static com.datadog.profiling.remoteconfig.RemoteConfiguration.Feature.WALL_TIME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class RemoteConfigurationStackMergeTest {
  private final RemoteConfigurationStack stack = new RemoteConfigurationStack();

  @Test
  void emptyStackSetsNothing() {
    assertSame(RemoteConfiguration.EMPTY, stack.merged());
  }

  @Test
  void singleConfigurationIsUnchanged() {
    stack.push("a", 0, config("\"cpuTime\":true,\"wallTime\":false"));

    RemoteConfiguration merged = stack.merged();

    assertEquals(true, merged.get(CPU_TIME));
    assertEquals(false, merged.get(WALL_TIME));
    assertNull(merged.get(ALLOCATIONS));
  }

  @Test
  void highestPriorityValueWinsWhateverTheConfigId() {
    // by config id, "z" would overwrite "a" in both stacks
    stack.push("a", 3001, config("\"cpuTime\":true"));
    stack.push("z", 0, config("\"cpuTime\":false"));
    assertEquals(true, stack.merged().get(CPU_TIME));

    RemoteConfigurationStack reversed = new RemoteConfigurationStack();
    reversed.push("a", 0, config("\"cpuTime\":false"));
    reversed.push("z", 3001, config("\"cpuTime\":true"));
    assertEquals(true, reversed.merged().get(CPU_TIME));
  }

  @Test
  void disablingWithAHigherPriorityOverridesEnabling() {
    stack.push("general", 0, config("\"cpuTime\":true,\"wallTime\":true"));
    stack.push("specific", 3001, config("\"cpuTime\":false"));

    RemoteConfiguration merged = stack.merged();

    assertEquals(false, merged.get(CPU_TIME));
    assertEquals(true, merged.get(WALL_TIME));
  }

  @Test
  void equalPriorityGivesTheValueOfTheGreatestConfigId() {
    stack.push("b", 2001, config("\"cpuTime\":false"));
    stack.push("a", 2001, config("\"cpuTime\":true"));
    stack.push("c", 2001, config("\"cpuTime\":true"));

    assertEquals(true, stack.merged().get(CPU_TIME));
  }

  @Test
  void configurationsAreMergedFeatureByFeature() {
    stack.push("general", 0, config("\"cpuTime\":true,\"wallTime\":false,\"heapObjects\":true"));
    stack.push("specific", 2001, config("\"wallTime\":true,\"allocations\":true"));

    RemoteConfiguration merged = stack.merged();

    assertEquals(true, merged.get(CPU_TIME), "only set by the general one");
    assertEquals(true, merged.get(WALL_TIME), "overwritten by the specific one");
    assertEquals(true, merged.get(HEAP_OBJECTS), "only set by the general one");
    assertEquals(true, merged.get(ALLOCATIONS), "only set by the specific one");
  }

  @Test
  void featureThatTheHigherPriorityDoesNotSetKeepsTheLowerPriorityValue() {
    stack.push("general", 0, config("\"wallTime\":false"));
    stack.push("specific", 3001, config("\"cpuTime\":true"));

    assertEquals(false, stack.merged().get(WALL_TIME));
  }

  @Test
  void configurationWithoutFeaturesChangesNothing() {
    stack.push("general", 0, config("\"cpuTime\":true"));
    stack.push("specific", 7001, RemoteConfiguration.EMPTY);

    assertEquals(true, stack.merged().get(CPU_TIME));
  }

  @Test
  void replacedConfigurationNoLongerCounts() {
    stack.push("a", 3001, config("\"cpuTime\":false"));
    stack.push("b", 0, config("\"cpuTime\":true"));
    stack.push("a", 3001, config("\"wallTime\":true"));

    RemoteConfiguration merged = stack.merged();

    assertEquals(true, merged.get(CPU_TIME));
    assertEquals(true, merged.get(WALL_TIME));
  }

  @Test
  void removedConfigurationNoLongerCounts() {
    stack.push("general", 0, config("\"cpuTime\":true"));
    stack.push("specific", 3001, config("\"cpuTime\":false"));
    stack.remove("specific");

    assertEquals(true, stack.merged().get(CPU_TIME));
  }

  private static RemoteConfiguration config(String profilers) {
    return RemoteConfiguration.parse("{\"profilers\":{" + profilers + "}}");
  }
}
