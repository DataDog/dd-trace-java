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
  void highestPriorityValueWinsWhateverTheOrderItWasPushedIn() {
    // by push order, the lower priority one would overwrite the other in the first stack
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
  void equalPriorityGivesTheValueOfTheLatestPushed() {
    stack.push("c", 2001, config("\"cpuTime\":true"));
    stack.push("a", 2001, config("\"cpuTime\":true"));
    stack.push("b", 2001, config("\"cpuTime\":false"));

    assertEquals(false, stack.merged().get(CPU_TIME), "b is the latest");
  }

  @Test
  void updatedConfigurationBecomesTheLatestOfItsPriority() {
    stack.push("a", 2001, config("\"cpuTime\":true"));
    stack.push("b", 2001, config("\"cpuTime\":false"));
    stack.push("a", 2001, config("\"cpuTime\":true"));

    assertEquals(true, stack.merged().get(CPU_TIME), "a was updated after b");
  }

  @Test
  void latestOfAPriorityReplacesTheEarlierOnesEntirely() {
    stack.push("a", 2001, config("\"cpuTime\":true,\"wallTime\":true"));
    stack.push("b", 2001, config("\"cpuTime\":false"));

    RemoteConfiguration merged = stack.merged();

    assertEquals(false, merged.get(CPU_TIME));
    assertNull(merged.get(WALL_TIME), "a doesn't count, b being later with the same priority");
  }

  @Test
  void earlierConfigurationCountsAgainWhenTheLatestOfItsPriorityIsRemoved() {
    stack.push("a", 2001, config("\"cpuTime\":true,\"wallTime\":true"));
    stack.push("b", 2001, config("\"cpuTime\":false"));
    stack.remove("b");

    RemoteConfiguration merged = stack.merged();

    assertEquals(true, merged.get(CPU_TIME));
    assertEquals(true, merged.get(WALL_TIME));
  }

  @Test
  void lowerPriorityStillCountsForTheFeaturesTheLatestDoesNotSet() {
    stack.push("general", 0, config("\"wallTime\":false"));
    stack.push("a", 2001, config("\"cpuTime\":true"));
    stack.push("b", 2001, config("\"allocations\":true"));

    RemoteConfiguration merged = stack.merged();

    assertEquals(false, merged.get(WALL_TIME), "from the lower priority");
    assertNull(merged.get(CPU_TIME), "a doesn't count");
    assertEquals(true, merged.get(ALLOCATIONS));
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
