package com.datadog.profiling.remoteconfig;

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.profiling.remoteconfig.RemoteConfigurationStack.Entry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RemoteConfigurationStackTest {
  private static final RemoteConfiguration CPU =
      RemoteConfiguration.parse("{\"profilers\":{\"cpuTime\":true}}");
  private static final RemoteConfiguration WALL =
      RemoteConfiguration.parse("{\"profilers\":{\"wallTime\":true}}");

  private final RemoteConfigurationStack stack = new RemoteConfigurationStack();

  @Test
  void newStackIsEmpty() {
    assertTrue(stack.isEmpty());
    assertTrue(stack.lowestToHighestPriority().isEmpty());
    assertTrue(stack.configIds().isEmpty());
  }

  @Test
  void entriesAreOrderedByPriority() {
    stack.push("a", 3001, CPU);
    stack.push("b", 0, WALL);
    stack.push("c", 2001, CPU);

    assertEquals(asList("b", "c", "a"), configIds(stack.lowestToHighestPriority()));
  }

  @Test
  void entriesWithTheSamePriorityAreOrderedByConfigIdWhateverTheOrderTheyWerePushedIn() {
    stack.push("c", 5, CPU);
    stack.push("a", 5, CPU);
    stack.push("b", 5, CPU);

    assertEquals(asList("a", "b", "c"), configIds(stack.lowestToHighestPriority()));
  }

  @Test
  void pushingTheSameConfigIdReplacesTheEntry() {
    stack.push("a", 1, CPU);
    stack.push("a", 5, WALL);

    List<Entry> entries = stack.lowestToHighestPriority();
    assertEquals(1, entries.size());
    assertEquals(5, entries.get(0).priority());
    assertSame(WALL, entries.get(0).configuration());
  }

  @Test
  void removeAndContains() {
    stack.push("a", 1, CPU);
    stack.push("b", 2, WALL);

    assertTrue(stack.contains("a"));
    stack.remove("a");
    stack.remove("unknown");

    assertFalse(stack.contains("a"));
    assertTrue(stack.contains("b"));
    assertFalse(stack.isEmpty());
    stack.remove("b");
    assertTrue(stack.isEmpty());
  }

  @Test
  void configIdsAreSorted() {
    stack.push("b", 1, CPU);
    stack.push("a", 2, CPU);

    assertEquals(asList("a", "b"), new ArrayList<>(stack.configIds()));
  }

  @Test
  void snapshotIsNotAffectedByLaterChanges() {
    stack.push("a", 1, CPU);

    RemoteConfigurationStack snapshot = stack.snapshot();
    stack.push("b", 2, WALL);
    stack.remove("a");

    assertEquals(asList("a"), configIds(snapshot.lowestToHighestPriority()));
    assertEquals(asList("b"), configIds(stack.lowestToHighestPriority()));
  }

  @Test
  void changesToASnapshotDoNotAffectTheStack() {
    stack.push("a", 1, CPU);

    stack.snapshot().push("b", 2, WALL);

    assertFalse(stack.contains("b"));
  }

  private static List<String> configIds(List<Entry> entries) {
    List<String> ids = new ArrayList<>();
    for (Entry entry : entries) {
      ids.add(entry.configId());
    }
    return ids;
  }
}
