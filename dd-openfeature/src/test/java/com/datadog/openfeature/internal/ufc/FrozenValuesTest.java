package com.datadog.openfeature.internal.ufc;

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openfeature.sdk.Value;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FrozenValuesTest {
  @Test
  void freezesToAValueEqualToObjectToValue() {
    final Map<String, Object> tree =
        object(
            "plan",
            "gold",
            "limits",
            object("n", 5, "big", 3_000_000_000L),
            "tags",
            asList("a", null, true, 1.5));

    final Value frozen = new FrozenValues.Freezer().freeze(tree);

    assertEquals(Value.objectToValue(tree), frozen);
    assertEquals(frozen, Value.objectToValue(tree));
    assertTrue(frozen.asStructure().asUnmodifiableMap() instanceof Map);
  }

  @Test
  void keepsTheKeyOrderOfObjectToValue() {
    final Map<String, Object> tree = new HashMap<>();
    for (int i = 0; i < 40; i++) {
      tree.put("key-" + i, i);
    }

    final Value frozen = new FrozenValues.Freezer().freeze(tree);

    assertEquals(
        new ArrayList<>(Value.objectToValue(tree).asStructure().keySet()),
        new ArrayList<>(frozen.asStructure().keySet()));
  }

  @Test
  void sharesRepeatedStringsAndScalars() {
    final FrozenValues.Freezer freezer = new FrozenValues.Freezer();
    final Value a = freezer.freeze(object(new String("tier"), new String("gold"), "n", 1_000));
    final Value b = freezer.freeze(object(new String("tier"), new String("gold"), "n", 1_000));

    assertNotSame(a, b, "objects are not shared");
    final Map<String, Value> left = a.asStructure().asUnmodifiableMap();
    final Map<String, Value> right = b.asStructure().asUnmodifiableMap();
    assertSame(left.get("tier"), right.get("tier"));
    assertSame(left.get("n"), right.get("n"));
    assertSame(left.keySet().iterator().next(), right.keySet().iterator().next());
  }

  @Test
  void keepsEqualLookingScalarsOfDifferentTypesApart() {
    final FrozenValues.Freezer freezer = new FrozenValues.Freezer();
    final List<Value> items = freezer.freeze(asList(1, 1L, 1.0, "1", 1)).asList();

    assertEquals(Value.objectToValue(asList(1, 1L, 1.0, "1", 1)).asList(), items);
    assertNotSame(items.get(0), items.get(1));
    assertNotSame(items.get(0), items.get(2));
    assertSame(items.get(0), items.get(4));
  }

  @Test
  void aCacheCollisionReplacesTheSlotWithoutMixingValues() {
    final FrozenValues.Freezer freezer = new FrozenValues.Freezer();
    final List<Object> numbers = new ArrayList<>();
    for (int i = 0; i < FrozenValues.Freezer.CACHE_SLOTS * 4; i++) {
      numbers.add(i * 7919);
    }

    assertEquals(Value.objectToValue(numbers), freezer.freeze(numbers));
  }

  @Test
  void doesNotShareLongStrings() {
    final FrozenValues.Freezer freezer = new FrozenValues.Freezer();
    final StringBuilder longText = new StringBuilder();
    while (longText.length() <= FrozenValues.Freezer.MAX_SHARED_LENGTH) {
      longText.append("abcdefgh");
    }
    final String longKey = longText.toString();
    final List<Value> items =
        freezer.freeze(asList(new String(longKey), new String(longKey))).asList();
    final Map<String, Value> one =
        freezer.freeze(object(new String(longKey), 1)).asStructure().asUnmodifiableMap();
    final Map<String, Value> two =
        freezer.freeze(object(new String(longKey), 1)).asStructure().asUnmodifiableMap();

    assertEquals(items.get(0), items.get(1));
    assertNotSame(items.get(0), items.get(1));
    assertNotSame(one.keySet().iterator().next(), two.keySet().iterator().next());
    assertEquals(one, two);
  }

  @Test
  void freezesNullsAndBooleansToSharedConstants() {
    final List<Value> items =
        new FrozenValues.Freezer().freeze(asList(null, true, false, true)).asList();

    assertEquals(Value.objectToValue(asList(null, true, false, true)).asList(), items);
    assertSame(items.get(1), items.get(3));
  }

  @Test
  void theParserSharesStringsAcrossFlags() throws Exception {
    final String json =
        "{\"createdAt\":\"2024-01-01T00:00:00Z\",\"format\":\"SERVER\",\"environment\":{\"name\":\"t\"},"
            + "\"flags\":{"
            + flag("f1")
            + ","
            + flag("f2")
            + "}}";

    final ServerConfiguration configuration =
        UniversalFlagConfigParser.parse(json.getBytes(StandardCharsets.UTF_8));

    final Map<String, Value> one = attributes(configuration, "f1");
    final Map<String, Value> two = attributes(configuration, "f2");
    assertEquals(one, two);
    assertSame(one.get("color"), two.get("color"));
  }

  private static Map<String, Value> attributes(
      final ServerConfiguration configuration, final String flag) {
    return ((Value) configuration.flags.get(flag).variations.get("on").value)
        .asStructure()
        .asUnmodifiableMap();
  }

  private static String flag(final String key) {
    return "\""
        + key
        + "\":{\"key\":\""
        + key
        + "\",\"enabled\":true,\"variationType\":\"JSON\","
        + "\"variations\":{\"on\":{\"key\":\"on\",\"value\":{\"color\":\"blue\",\"sizes\":[1,2]}}},"
        + "\"allocations\":[]}";
  }

  private static Map<String, Object> object(final Object... keysAndValues) {
    final Map<String, Object> map = new HashMap<>();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      map.put((String) keysAndValues[i], keysAndValues[i + 1]);
    }
    return map;
  }
}
