package com.datadog.profiling.remoteconfig;

import static com.datadog.profiling.remoteconfig.TestConfigKey.configKey;
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
    stack.push(configKey("a"), 3001, CPU);
    stack.push(configKey("b"), 0, WALL);
    stack.push(configKey("c"), 2001, CPU);

    assertEquals(asList("b", "c", "a"), configIds(stack.lowestToHighestPriority()));
  }

  @Test
  void entriesWithTheSamePriorityAreOrderedByTheOrderTheyWerePushedIn() {
    stack.push(configKey("c"), 5, CPU);
    stack.push(configKey("a"), 5, CPU);
    stack.push(configKey("b"), 5, CPU);

    assertEquals(asList("c", "a", "b"), configIds(stack.lowestToHighestPriority()));
  }

  @Test
  void pushingAgainMakesTheEntryTheLatestOfItsPriority() {
    stack.push(configKey("a"), 5, CPU);
    stack.push(configKey("b"), 5, CPU);
    stack.push(configKey("c"), 5, CPU);
    stack.push(configKey("a"), 5, WALL);

    assertEquals(asList("b", "c", "a"), configIds(stack.lowestToHighestPriority()));
  }

  @Test
  void snapshotKeepsTheOrderAndLaterPushesStayLatest() {
    stack.push(configKey("b"), 5, CPU);
    stack.push(configKey("a"), 5, CPU);

    RemoteConfigurationStack snapshot = stack.snapshot();
    snapshot.push(configKey("c"), 5, CPU);
    stack.push(configKey("d"), 5, CPU);

    assertEquals(asList("b", "a", "c"), configIds(snapshot.lowestToHighestPriority()));
    assertEquals(asList("b", "a", "d"), configIds(stack.lowestToHighestPriority()));
  }

  @Test
  void pushingTheSameConfigIdReplacesTheEntry() {
    stack.push(configKey("a"), 1, CPU);
    stack.push(configKey("a"), 5, WALL);

    List<Entry> entries = stack.lowestToHighestPriority();
    assertEquals(1, entries.size());
    assertEquals(5, entries.get(0).priority());
    assertSame(WALL, entries.get(0).configuration());
  }

  @Test
  void removeAndContains() {
    stack.push(configKey("a"), 1, CPU);
    stack.push(configKey("b"), 2, WALL);

    assertTrue(stack.contains(configKey("a")));
    stack.remove(configKey("a"));
    stack.remove(configKey("unknown"));

    assertFalse(stack.contains(configKey("a")));
    assertTrue(stack.contains(configKey("b")));
    assertFalse(stack.isEmpty());
    stack.remove(configKey("b"));
    assertTrue(stack.isEmpty());
  }

  @Test
  void configIdsAreSorted() {
    stack.push(configKey("b"), 1, CPU);
    stack.push(configKey("a"), 2, CPU);

    assertEquals(asList("a", "b"), new ArrayList<>(stack.configIds()));
  }

  @Test
  void snapshotIsNotAffectedByLaterChanges() {
    stack.push(configKey("a"), 1, CPU);

    RemoteConfigurationStack snapshot = stack.snapshot();
    stack.push(configKey("b"), 2, WALL);
    stack.remove(configKey("a"));

    assertEquals(asList("a"), configIds(snapshot.lowestToHighestPriority()));
    assertEquals(asList("b"), configIds(stack.lowestToHighestPriority()));
  }

  @Test
  void changesToASnapshotDoNotAffectTheStack() {
    stack.push(configKey("a"), 1, CPU);

    stack.snapshot().push(configKey("b"), 2, WALL);

    assertFalse(stack.contains(configKey("b")));
  }

  private static List<String> configIds(List<Entry> entries) {
    List<String> ids = new ArrayList<>();
    for (Entry entry : entries) {
      ids.add(entry.configId());
    }
    return ids;
  }
}
