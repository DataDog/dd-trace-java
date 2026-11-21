package com.datadog.openfeature.internal.ufc;

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.openfeature.sdk.Value;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FrozenValuesTest {
  @Test
  void aFreezerSharesEqualPartsAcrossTrees() {
    final FrozenValues.Freezer freezer = new FrozenValues.Freezer();
    final Map<String, Object> first = object("plan", new String("gold"), "limits", object("n", 5));
    final Map<String, Object> second = object("plan", new String("gold"), "limits", object("n", 5));

    final Value a = freezer.freeze(first);
    final Value b = freezer.freeze(second);

    assertEquals(Value.objectToValue(first), a);
    assertSame(a, b, "equal trees freeze to one instance");
  }

  @Test
  void sharedLeavesAndSubtreesInsideDifferentTrees() {
    final FrozenValues.Freezer freezer = new FrozenValues.Freezer();
    final Value a = freezer.freeze(object("tier", new String("gold"), "list", asList(1, 2)));
    final Value b =
        freezer.freeze(object("tier", new String("gold"), "list", asList(1, 2), "x", true));

    assertNotSame(a, b);
    final Map<String, Value> left = a.asStructure().asUnmodifiableMap();
    final Map<String, Value> right = b.asStructure().asUnmodifiableMap();
    assertSame(left.get("tier"), right.get("tier"));
    assertSame(left.get("tier").asString(), right.get("tier").asString());
    assertSame(left.get("list"), right.get("list"));
    final List<Value> items = left.get("list").asList();
    assertSame(items.get(0), freezer.freeze(asList(1)).asList().get(0));
  }

  @Test
  void keepsEqualLookingScalarsOfDifferentTypesApart() {
    final FrozenValues.Freezer freezer = new FrozenValues.Freezer();
    final Value integer = freezer.freeze(asList(1));
    final Value decimal = freezer.freeze(asList(1.0));
    final Value text = freezer.freeze(asList("1"));

    assertNotSame(integer, decimal);
    assertNotSame(decimal, text);
    assertEquals(Value.objectToValue(asList(1)), integer);
    assertEquals(Value.objectToValue(asList(1.0)), decimal);
  }

  @Test
  void aStructureDiffersFromOneWithTheSameChildrenUnderOtherKeys() {
    final FrozenValues.Freezer freezer = new FrozenValues.Freezer();

    assertNotSame(freezer.freeze(object("a", 1, "b", 2)), freezer.freeze(object("a", 2, "b", 1)));
    assertNotSame(freezer.freeze(object("a", 1)), freezer.freeze(object("a", 1, "b", 1)));
    assertNotSame(freezer.freeze(asList(1, 2)), freezer.freeze(asList(2, 1)));
    assertNotSame(freezer.freeze(asList(1)), freezer.freeze(asList(1, 1)));
  }

  @Test
  void theParserSharesEqualJsonValuesAcrossFlags() throws Exception {
    final String json =
        "{\"createdAt\":\"2024-01-01T00:00:00Z\",\"format\":\"SERVER\",\"environment\":{\"name\":\"t\"},"
            + "\"flags\":{"
            + flag("f1")
            + ","
            + flag("f2")
            + "}}";

    final ServerConfiguration configuration =
        UniversalFlagConfigParser.parse(json.getBytes(StandardCharsets.UTF_8));

    assertSame(
        configuration.flags.get("f1").variations.get("on").value,
        configuration.flags.get("f2").variations.get("on").value);
  }

  @Test
  void tableKeysCompareByTypeAndIdentity() {
    final Value one = Value.objectToValue(1);
    final Map<String, Value> attributes = new HashMap<>();
    attributes.put("a", one);
    final Map<String, Value> otherKey = new HashMap<>();
    otherKey.put("b", one);
    final Map<String, Value> twoKeys = new HashMap<>(attributes);
    twoKeys.put("b", one);

    final FrozenValues.StructureKey structure = new FrozenValues.StructureKey(attributes);
    assertEquals(structure, new FrozenValues.StructureKey(new HashMap<>(attributes)));
    assertNotEquals(structure, new FrozenValues.StructureKey(otherKey));
    assertNotEquals(structure, new FrozenValues.StructureKey(twoKeys));
    assertNotEquals(structure, new FrozenValues.ListKey(asList(one)));
    // "Aa" and "BB" have the same String hash, so these keys collide and compare entry by entry.
    final Map<String, Value> aa = new HashMap<>();
    aa.put("Aa", one);
    final Map<String, Value> bb = new HashMap<>();
    bb.put("BB", one);
    assertNotEquals(new FrozenValues.StructureKey(aa), new FrozenValues.StructureKey(bb));

    final FrozenValues.ListKey list = new FrozenValues.ListKey(asList(one));
    assertEquals(list, new FrozenValues.ListKey(asList(one)));
    assertNotEquals(list, new FrozenValues.ListKey(asList(Value.objectToValue(1))));
    assertNotEquals(list, new FrozenValues.ListKey(asList(one, one)));
    assertNotEquals(list, structure);

    final FrozenValues.ScalarKey scalar = new FrozenValues.ScalarKey(1);
    assertEquals(scalar, new FrozenValues.ScalarKey(1));
    assertEquals(scalar.hashCode(), new FrozenValues.ScalarKey(1).hashCode());
    assertNotEquals(scalar, new FrozenValues.ScalarKey(1L));
    assertNotEquals(scalar, new FrozenValues.ScalarKey(2));
    assertNotEquals(scalar, "1");
  }

  @Test
  void freezesNullsAndBooleansToSharedConstants() {
    final FrozenValues.Freezer freezer = new FrozenValues.Freezer();
    final List<Value> items = freezer.freeze(asList(null, true, false, true)).asList();

    assertEquals(Value.objectToValue(asList(null, true, false, true)).asList(), items);
    assertSame(items.get(1), items.get(3));
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
