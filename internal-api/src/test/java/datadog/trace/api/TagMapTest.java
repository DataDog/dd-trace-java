package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import datadog.trace.api.TagMap.EntryIterator;

public class TagMapTest {
  // size is chosen to make sure to stress all types of collisions in the Map
  static final int MANY_SIZE = 256;

  // static function tests - mostly exist to satisfy coverage checker
  @Test
  public void fromMap_emptyMap() {
    Map<String, String> emptyMap = Collections.emptyMap();

    TagMap tagMap = TagMap.fromMap(emptyMap);
    assertEquals(0, tagMap.size());
    assertTrue(tagMap.isEmpty());

    assertFalse(tagMap.isFrozen());
  }

  @Test
  public void fromMap_nonEmptyMap() {
    // mostly exists to satisfy coverage checker
    HashMap<String, String> origMap = new HashMap<>();
    origMap.put("foo", "bar");
    origMap.put("baz", "quux");

    TagMap tagMap = TagMap.fromMap(origMap);
    assertEquals(tagMap.size(), origMap.size());

    assertEquals(tagMap.get("foo"), origMap.get("foo"));
    assertEquals(tagMap.get("baz"), origMap.get("baz"));

    assertFalse(tagMap.isFrozen());
  }

  @Test
  public void fromMapImmutable_empty() {
    Map<String, String> emptyMap = Collections.emptyMap();

    TagMap tagMap = TagMap.fromMapImmutable(emptyMap);
    assertEquals(0, tagMap.size());
    assertTrue(tagMap.isEmpty());

    assertTrue(tagMap.isFrozen());
  }

  @Test
  public void fromMapImmutable_nonEmptyMap() {
    // mostly exists to satisfy coverage checker
    HashMap<String, String> origMap = new HashMap<>();
    origMap.put("foo", "bar");
    origMap.put("baz", "quux");

    TagMap tagMap = TagMap.fromMapImmutable(origMap);
    assertEquals(tagMap.size(), origMap.size());

    assertEquals(tagMap.get("foo"), origMap.get("foo"));
    assertEquals(tagMap.get("baz"), origMap.get("baz"));

    assertTrue(tagMap.isFrozen());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  public void optimizedFactory(boolean optimized) {
    TagMapFactory<?> factory = TagMapFactory.createFactory(optimized);

    TagMap unsizedMap = factory.create();
    assertEquals(optimized, unsizedMap.isOptimized());

    TagMap sizedMap = factory.create(32);
    assertEquals(optimized, sizedMap.isOptimized());

    TagMap emptyMap = factory.empty();
    assertEquals(optimized, emptyMap.isOptimized());
  }

  @ParameterizedTest
  @EnumSource(TagMapScenario.class)
  public void map_put(TagMapScenario scenario) {
    TagMap map = scenario.create();

    Object prev = map.put("foo", "bar");
    assertNull(prev);

    assertEntry("foo", "bar", map);

    assertSize(scenario.size() + 1, map);
    assertNotEmpty(map);

    checkIntegrity(map);
  }

  @ParameterizedTest
  @EnumSource(TagMapScenario.class)
  public void booleanEntry(TagMapScenario scenario) {
    String BOOL = "bool";
    String UNSET = "unset";

    boolean first = false;
    boolean second = true;

    TagMap map = scenario.create();
    map.set(BOOL, first);

    TagMap.Entry firstEntry = map.getEntry(BOOL);
    if (map.isOptimized()) {
      assertEquals(TagMap.Entry.BOOLEAN, firstEntry.rawType);
    }

    assertEquals(first, firstEntry.booleanValue());
    assertEquals(first, map.getBoolean(BOOL));

    TagMap.Entry priorEntry = map.getAndSet(BOOL, second);
    if (map.isOptimized()) {
      assertSame(priorEntry, firstEntry);
    }
    assertEquals(first, priorEntry.booleanValue());

    TagMap.Entry newEntry = map.getEntry(BOOL);
    assertEquals(second, newEntry.booleanValue());

    assertEquals(false, map.getBoolean(UNSET));
    assertEquals(true, map.getBooleanOrDefault(UNSET, true));

    checkIntegrity(map);
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  public void numericZeroToBooleanCoercion(TagMapType mapType) {
    TagMap map =
        TagMap.ledger()
            .set("int", 0)
            .set("intObj", Integer.valueOf(0))
            .set("long", 0L)
            .set("longObj", Long.valueOf(0L))
            .set("float", 0F)
            .set("floatObj", Float.valueOf(0F))
            .set("double", 0D)
            .set("doubleObj", Double.valueOf(0D))
            .build(mapType.factory);

    assertBoolean(false, map, "int");
    assertBoolean(false, map, "intObj");
    assertBoolean(false, map, "long");
    assertBoolean(false, map, "longObj");
    assertBoolean(false, map, "float");
    assertBoolean(false, map, "floatObj");
    assertBoolean(false, map, "double");
    assertBoolean(false, map, "doubleObj");

    checkIntegrity(map);
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  public void numericNonZeroToBooleanCoercion(TagMapType mapType) {
    TagMap map =
        TagMap.ledger()
            .set("int", 1)
            .set("intObj", Integer.valueOf(1))
            .set("long", 1L)
            .set("longObj", Long.valueOf(1L))
            .set("float", 1F)
            .set("floatObj", Float.valueOf(1F))
            .set("double", 1D)
            .set("doubleObj", Double.valueOf(1D))
            .build(mapType.factory);

    assertBoolean(true, map, "int");
    assertBoolean(true, map, "intObj");
    assertBoolean(true, map, "long");
    assertBoolean(true, map, "longObj");
    assertBoolean(true, map, "float");
    assertBoolean(true, map, "floatObj");
    assertBoolean(true, map, "double");
    assertBoolean(true, map, "doubleObj");

    checkIntegrity(map);
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  public void objectToBooleanCoercion(TagMapType mapType) {
    TagMap map =
        TagMap.ledger()
            .set("obj", new Object())
            .set("trueStr", "true")
            .set("falseStr", "false")
            .build(mapType.factory);

    assertBoolean(true, map, "obj");
    assertBoolean(true, map, "trueStr");
    assertBoolean(true, map, "falseStr");

    checkIntegrity(map);
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  public void booleanToNumericCoercion_true(TagMapType mapType) {
    TagMap map = TagMap.ledger().set("true", true).build(mapType.factory);

    assertInt(1, map, "true");
    assertLong(1L, map, "true");
    assertFloat(1F, map, "true");
    assertDouble(1D, map, "true");

    checkIntegrity(map);
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  public void booleanToNumericCoercion_false(TagMapType mapType) {
    TagMap map = TagMap.ledger().set("false", false).build(mapType.factory);

    assertInt(0, map, "false");
    assertLong(0L, map, "false");
    assertFloat(0F, map, "false");
    assertDouble(0D, map, "false");

    checkIntegrity(map);
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  public void emptyToPrimitiveCoercion(TagMapType mapType) {
    TagMap map = mapType.empty();

    // DQH - assert<type> helpers also check get<type>OrDefault, so they don't work here
    assertEquals(false, map.getBoolean("dne"));
    assertEquals(0, map.getInt("dne"));
    assertEquals(0L, map.getLong("dne"));
    assertEquals(0F, map.getFloat("dne"));
    assertEquals(0D, map.getDouble("dne"));

    checkIntegrity(map);
  }

  @ParameterizedTest
  @EnumSource(TagMapScenario.class)
  public void intEntry(TagMapScenario scenario) {
    String INT = "int";
    String UNSET = "unset";

    int first = 3142;
    int second = 2718;

    TagMap map = scenario.create();
    map.set(INT, first);

    TagMap.Entry firstEntry = map.getEntry("int");
    if (map.isOptimized()) {
      assertEquals(TagMap.Entry.INT, firstEntry.rawType);
    }

    assertEquals(first, firstEntry.intValue());
    assertEquals(first, map.getInt("int"));

    TagMap.Entry priorEntry = map.getAndSet("int", second);
    if (map.isOptimized()) {
      assertSame(priorEntry, firstEntry);
    }
    assertEquals(first, priorEntry.intValue());

    TagMap.Entry newEntry = map.getEntry("int");
    assertEquals(second, newEntry.intValue());
    assertEquals(0, map.getInt(UNSET));
    assertEquals(21, map.getIntOrDefault(UNSET, 21));

    checkIntegrity(map);
  }

  @ParameterizedTest
  @EnumSource(TagMapScenario.class)
  public void longEntry(TagMapScenario scenario) {
    String LONG = "long";
    String UNSET = "unset";

    long first = 3142L;
    long second = 2718L;

    TagMap map = scenario.create();
    map.set(LONG, first);

    TagMap.Entry firstEntry = map.getEntry("long");
    if (map.isOptimized()) {
      assertEquals(TagMap.Entry.LONG, firstEntry.rawType);
    }

    assertEquals(first, firstEntry.longValue());
    assertEquals(first, map.getLong("long"));

    TagMap.Entry priorEntry = map.getAndSet("long", second);
    if (map.isOptimized()) {
      assertSame(priorEntry, firstEntry);
    }
    assertEquals(first, priorEntry.longValue());

    TagMap.Entry newEntry = map.getEntry("long");
    assertEquals(second, newEntry.longValue());

    assertEquals(0L, map.getLong(UNSET));
    assertEquals(21L, map.getLongOrDefault(UNSET, 21L));

    checkIntegrity(map);
  }

  @ParameterizedTest
  @EnumSource(TagMapScenario.class)
  public void floatEntry(TagMapScenario scenario) {
    String FLOAT = "float";
    String UNSET = "unset";

    float first = 3.14F;
    float second = 2.718F;

    TagMap map = scenario.create();
    map.set(FLOAT, first);

    TagMap.Entry firstEntry = map.getEntry(FLOAT);
    if (map.isOptimized()) {
      assertEquals(TagMap.Entry.FLOAT, firstEntry.rawType);
    }

    assertEquals(first, firstEntry.floatValue());
    assertEquals(first, map.getFloat(FLOAT));

    TagMap.Entry priorEntry = map.getAndSet(FLOAT, second);
    if (map.isOptimized()) {
      assertSame(priorEntry, firstEntry);
    }
    assertEquals(first, priorEntry.floatValue());

    TagMap.Entry newEntry = map.getEntry(FLOAT);
    assertEquals(second, newEntry.floatValue());

    assertEquals(0F, map.getFloat(UNSET));
    assertEquals(2.718F, map.getFloatOrDefault(UNSET, 2.718F));

    checkIntegrity(map);
  }

  @ParameterizedTest
  @EnumSource(TagMapScenario.class)
  public void doubleEntry(TagMapScenario scenario) {
    String DOUBLE = "double";
    String UNSET = "unset";

    double first = Math.PI;
    double second = Math.E;

    TagMap map = scenario.create();
    map.set(DOUBLE, Math.PI);

    TagMap.Entry firstEntry = map.getEntry(DOUBLE);
    if (map.isOptimized()) {
      assertEquals(TagMap.Entry.DOUBLE, firstEntry.rawType);
    }

    assertEquals(first, firstEntry.doubleValue());
    assertEquals(first, map.getDouble(DOUBLE));

    TagMap.Entry priorEntry = map.getAndSet(DOUBLE, second);
    if (map.isOptimized()) {
      assertSame(priorEntry, firstEntry);
    }
    assertEquals(first, priorEntry.doubleValue());

    TagMap.Entry newEntry = map.getEntry(DOUBLE);
    assertEquals(second, newEntry.doubleValue());

    assertEquals(0D, map.getDouble(UNSET));
    assertEquals(2.718D, map.getDoubleOrDefault(UNSET, 2.718D));

    checkIntegrity(map);
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  public void empty(TagMapType mapType) {
    TagMap empty = mapType.empty();
    assertFrozen(empty);

    assertNull(empty.getEntry("foo"));
    assertSize(0, empty);
    assertEmpty(empty);

    checkIntegrity(empty);
  }

  @ParameterizedTest
  @EnumSource(TagMapTypePair.class)
  public void putAll_empty(TagMapTypePair mapTypePair) {
    // TagMap.EMPTY breaks the rules and uses a different size bucket array
    // This test is just to verify that the common use of putAll still works with EMPTY
    TagMap newMap = mapTypePair.firstType.create();
    newMap.putAll(mapTypePair.secondType.empty());

    assertSize(0, newMap);
    assertEmpty(newMap);

    checkIntegrity(newMap);
  }

  @ParameterizedTest
  @EnumSource(TagMapScenario.class)
  public void clear(TagMapScenario scenario) {
    TagMap map = scenario.create();
    assertSize(scenario.size(), map);

    assertEmptiness(scenario.size() == 0, map);

    map.clear();
    assertSize(0, map);
    assertEmpty(map);

    checkIntegrity(map);
  }

  @ParameterizedTest
  @EnumSource(TagMapScenario.class)
  public void map_put_replacement(TagMapScenario scenario) {
    TagMap map = scenario.create();
    Object prev1 = map.put("foo", "bar");
    assertNull(prev1);

    assertEntry("foo", "bar", map);
    assertSize(scenario.size() + 1, map);
    assertNotEmpty(map);

    Object prev2 = map.put("foo", "baz");
    assertSize(scenario.size() + 1, map);
    assertEquals("bar", prev2);

    assertEntry("foo", "baz", map);

    checkIntegrity(map);
  }

  @ParameterizedTest
  @EnumSource(TagMapScenario.class)
  public void map_remove_Object(TagMapScenario scenario) {
    TagMap map = scenario.create();

    Object prev = map.remove((Object) "foo");
    assertNull(prev);

    map.put("foo", "bar");
    assertEntry("foo", "bar", map);
    assertSize(scenario.size() + 1, map);
    assertNotEmpty(map);

    Object prev2 = map.remove((Object) "foo");
    assertEquals("bar", prev2);
    assertSize(scenario.size(), map);
    assertEmptiness(scenario, map);

    checkIntegrity(map);
  }

  @ParameterizedTest
  @EnumSource(TagMapScenario.class)
  public void map_remove_String(TagMapScenario scenario) {
    TagMap map = scenario.create();

    boolean hadPrev1 = map.remove("foo");
    assertFalse(hadPrev1);

    map.put("foo", "bar");
    assertEntry("foo", "bar", map);
    assertSize(scenario.size() + 1, map);
    assertNotEmpty(map);

    boolean hadPrev2 = map.remove("foo");
    assertTrue(hadPrev2);
    assertSize(scenario.size(), map);
    assertEmptiness(scenario, map);

    checkIntegrity(map);
  }

  @ParameterizedTest
  @EnumSource(TagMapScenario.class)
  public void map_getAndRemove(TagMapScenario scenario) {
    TagMap map = scenario.create();

    TagMap.Entry prevEntry1 = map.getAndRemove("foo");
    assertNull(prevEntry1);

    map.put("foo", "bar");
    assertEntry("foo", "bar", map);
    assertSize(scenario.size() + 1, map);
    assertNotEmpty(map);

    TagMap.Entry prevEntry2 = map.getAndRemove("foo");
    assertEquals("bar", prevEntry2.objectValue());
    assertSize(scenario.size(), map);
    assertEmptiness(scenario, map);

    checkIntegrity(map);
  }

  @ParameterizedTest
  @EnumSource(TagMapScenario.class)
  public void freeze(TagMapScenario scenario) {
    TagMap map = scenario.create();
    map.put("foo", "bar");

    assertEntry("foo", "bar", map);

    map.freeze();

    assertFrozen(
        () -> {
          map.remove("foo");
        });

    assertEntry("foo", "bar", map);

    checkIntegrity(map);
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  public void emptyMap(TagMapType mapType) {
    TagMap map = mapType.empty();

    assertSize(0, map);
    assertEmpty(map);

    assertFrozen(map);

    checkIntegrity(map);
  }

  @ParameterizedTest
  @EnumSource(TagMapScenario.class)
  public void putMany(TagMapScenario scenario) {
    TagMap map = scenario.create();

    int size = scenario.size();
    fillMap(map, size);

    for (int i = 0; i < size; ++i) {
      assertEntry(key(i), value(i), map);
    }

    assertEmptiness(scenario, map);
    assertSize(scenario.size() + size, map);

    checkIntegrity(map);
  }

  @ParameterizedTest
  @EnumSource(TagMapScenario.class)
  public void copyMany(TagMapScenario scenario) {
    int size = scenario.size();
    TagMap orig = scenario.create();
    fillMap(orig, size);

    assertSize(scenario.size() + size, orig);

    TagMap copy = orig.copy();
    orig.clear(); // doing this to make sure that copied isn't modified

    for (int i = 0; i < size; ++i) {
      assertEntry(key(i), value(i), copy);
    }
    assertSize(scenario.size() + size, copy);

    checkIntegrity(orig);
    checkIntegrity(copy);
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  public void immutableCopy(TagMapType mapType) {
    int size = randomSize();
    TagMap orig = createTagMap(mapType, size);

    TagMap immutableCopy = orig.immutableCopy();
    orig.clear(); // doing this to make sure that copied isn't modified

    for (int i = 0; i < size; ++i) {
      assertEntry(key(i), value(i), immutableCopy);
    }
    assertSize(size, immutableCopy);
    assertFrozen(immutableCopy);

    checkIntegrity(orig);
    checkIntegrity(immutableCopy);
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  public void replaceALot(TagMapType mapType) {
    int size = randomSize();
    TagMap map = createTagMap(mapType, size);

    for (int i = 0; i < size; ++i) {
      int index = ThreadLocalRandom.current().nextInt(size);

      map.put(key(index), altValue(index));
      assertEquals(altValue(index), map.get(key(index)));
    }

    checkIntegrity(map);
  }

  @ParameterizedTest
  @EnumSource(TagMapTypePair.class)
  public void shareEntry(TagMapTypePair mapTypePair) {
    TagMap orig = mapTypePair.firstType.create();
    orig.set("foo", "bar");

    TagMap dest = mapTypePair.secondType.create();
    dest.set(orig.getEntry("foo"));

    assertEquals(orig.getEntry("foo"), dest.getEntry("foo"));
    if (mapTypePair == TagMapTypePair.BOTH_OPTIMIZED) {
      assertSame(orig.getEntry("foo"), dest.getEntry("foo"));
    }

    checkIntegrity(orig);
    checkIntegrity(dest);
  }

  @ParameterizedTest
  @EnumSource(TagMapTypePair.class)
  public void putAll_clobberAll(TagMapTypePair mapTypePair) {
    int size = randomSize();
    TagMap orig = createTagMap(mapTypePair.firstType, size);
    assertSize(size, orig);

    TagMap dest = mapTypePair.secondType.create();
    for (int i = size - 1; i >= 0; --i) {
      dest.set(key(i), altValue(i));
    }

    // This should clobber all the values in dest
    dest.putAll(orig);

    for (int i = 0; i < size; ++i) {
      assertEntry(key(i), value(i), dest);
    }
    assertSize(size, dest);

    checkIntegrity(orig);
    checkIntegrity(dest);
  }

  @ParameterizedTest
  @EnumSource(TagMapScenario.class)
  public void putAll_cloberAll(TagMapScenario scenario) {
    int size = scenario.size();
    TagMap orig = scenario.create();
    assertSize(size, orig);

    TagMap dest = scenario.create();
    assertSize(size, dest);

    dest.putAll(orig);

    assertSize(size, orig);
    assertSize(size, dest);

    checkIntegrity(orig);
    checkIntegrity(dest);
  }

  @ParameterizedTest
  @EnumSource(TagMapTypePair.class)
  public void putAll_clobberAndExtras(TagMapTypePair mapTypePair) {
    int size = randomSize();
    TagMap orig = createTagMap(mapTypePair.firstType, size);
    assertSize(size, orig);

    TagMap dest = mapTypePair.secondType.create();
    for (int i = size / 2 - 1; i >= 0; --i) {
      dest.set(key(i), altValue(i));
    }

    // This should clobber all the values in dest
    dest.putAll(orig);

    for (int i = 0; i < size; ++i) {
      assertEntry(key(i), value(i), dest);
    }

    assertSize(size, dest);

    checkIntegrity(orig);
    checkIntegrity(dest);
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  public void removeMany(TagMapType mapType) {
    int size = randomSize();
    TagMap map = createTagMap(mapType, size);

    for (int i = 0; i < size; ++i) {
      assertEntry(key(i), value(i), map);
    }

    assertNotEmpty(map);
    assertSize(size, map);

    for (int i = 0; i < size; ++i) {
      Object removedValue = map.remove((Object) key(i));
      assertEquals(value(i), removedValue);

      // not doing exhaustive size checks
      assertEquals(size - i - 1, map.size());
    }

    assertEmpty(map);

    checkIntegrity(map);
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  public void fillMap(TagMapType mapType) {
    int size = randomSize();
    TagMap map = mapType.create();
    for (int i = 0; i < size; ++i) {
      map.set(key(i), i);
    }

    HashMap<String, Object> hashMap = new HashMap<>();
    map.fillMap(hashMap);

    for (int i = 0; i < size; ++i) {
      assertEquals(Integer.valueOf(i), hashMap.remove(key(i)));
    }
    assertTrue(hashMap.isEmpty());

    checkIntegrity(map);
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  public void fillStringMap(TagMapType mapType) {
    int size = randomSize();
    TagMap map = mapType.create();
    for (int i = 0; i < size; ++i) {
      map.set(key(i), i);
    }

    HashMap<String, String> hashMap = new HashMap<>();
    map.fillStringMap(hashMap);

    for (int i = 0; i < size; ++i) {
      assertEquals(Integer.toString(i), hashMap.remove(key(i)));
    }
    assertTrue(hashMap.isEmpty());

    checkIntegrity(map);
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  public void iterator(TagMapType mapType) {
    int size = randomSize();
    TagMap map = createTagMap(mapType, size);

    Set<String> keys = new HashSet<>();
    for (TagMap.EntryReader entry : map) {
      // makes sure that each key is visited once and only once
      assertTrue(keys.add(entry.tag()));
    }

    for (int i = 0; i < size; ++i) {
      // make sure the key was present
      assertTrue(keys.remove(key(i)));
    }

    // no extraneous keys
    assertTrue(keys.isEmpty());

    checkIntegrity(map);
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  public void forEachConsumer(TagMapType mapType) {
    int size = randomSize();
    TagMap map = createTagMap(mapType, size);

    Set<String> keys = new HashSet<>(size);
    map.forEach((entry) -> keys.add(entry.tag()));

    for (int i = 0; i < size; ++i) {
      // make sure the key was present
      assertTrue(keys.remove(key(i)));
    }

    // no extraneous keys
    assertTrue(keys.isEmpty());

    checkIntegrity(map);
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  public void forEachBiConsumer(TagMapType mapType) {
    int size = randomSize();
    TagMap map = createTagMap(mapType, size);

    Set<String> keys = new HashSet<>(size);
    map.forEach(keys, (k, entry) -> k.add(entry.tag()));

    for (int i = 0; i < size; ++i) {
      // make sure the key was present
      assertTrue(keys.remove(key(i)));
    }

    // no extraneous keys
    assertTrue(keys.isEmpty());

    checkIntegrity(map);
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  public void forEachTriConsumer(TagMapType mapType) {
    int size = randomSize();
    TagMap map = createTagMap(mapType, size);

    Set<String> keys = new HashSet<>(size);
    map.forEach(keys, "hi", (k, msg, entry) -> keys.add(entry.tag()));

    for (int i = 0; i < size; ++i) {
      // make sure the key was present
      assertTrue(keys.remove(key(i)));
    }

    // no extraneous keys
    assertTrue(keys.isEmpty());

    checkIntegrity(map);
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  public void entrySet(TagMapType mapType) {
    int size = randomSize();
    TagMap map = createTagMap(mapType, size);

    Set<Map.Entry<String, Object>> actualEntries = map.entrySet();
    assertEquals(size, actualEntries.size());
    assertFalse(actualEntries.isEmpty());

    Set<String> expectedKeys = expectedKeys(size);
    for (Map.Entry<String, Object> entry : actualEntries) {
      assertTrue(expectedKeys.remove(entry.getKey()));
    }
    assertTrue(expectedKeys.isEmpty());

    checkIntegrity(map);
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  public void keySet(TagMapType mapType) {
    int size = randomSize();
    TagMap map = createTagMap(mapType, size);

    Set<String> actualKeys = map.keySet();
    assertEquals(size, actualKeys.size());
    assertFalse(actualKeys.isEmpty());

    Set<String> expectedKeys = expectedKeys(size);
    for (String key : actualKeys) {
      assertTrue(expectedKeys.remove(key));
    }
    assertTrue(expectedKeys.isEmpty());

    checkIntegrity(map);
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  public void values(TagMapType mapType) {
    int size = randomSize();
    TagMap map = createTagMap(mapType, size);

    Collection<Object> actualValues = map.values();
    assertEquals(size, actualValues.size());
    assertFalse(actualValues.isEmpty());

    Set<String> expectedValues = expectedValues(size);
    for (Object value : map.values()) {
      assertTrue(expectedValues.remove(value));
    }
    assertTrue(expectedValues.isEmpty());

    checkIntegrity(map);
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  public void _toString(TagMapType mapType) {
    int size = 4;
    TagMap map = createTagMap(mapType, size);

    String str = map.toString();

    for (int i = 0; i < size; ++i) {
      assertTrue(str.contains(key(i) + "=" + value(i)));
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 5, 25, 125})
  public void _toInternalString(int size) {
    OptimizedTagMap tagMap = new OptimizedTagMap();
    fillMap(tagMap, size);

    String str = tagMap.toInternalString();

    for (int i = 0; i < size; ++i) {
      assertTrue(str.contains(key(i) + "=" + value(i)));
    }
  }

  static int randomSize() {
    return ThreadLocalRandom.current().nextInt(16, MANY_SIZE);
  }

  static TagMap createTagMap(TagMapType mapType) {
    return createTagMap(mapType, randomSize());
  }

  static TagMap createTagMap(TagMapType mapType, int size) {
    TagMap map = mapType.create();
    fillMap(map, size);
    return map;
  }

  static void fillMap(TagMap map, int size) {
    for (int i = 0; i < size; ++i) {
      map.set(key(i), value(i));
    }
  }

  static Set<String> expectedKeys(int size) {
    Set<String> set = new HashSet<String>(size);
    for (int i = 0; i < size; ++i) {
      set.add(key(i));
    }
    return set;
  }

  static Set<String> expectedValues(int size) {
    Set<String> set = new HashSet<String>(size);
    for (int i = 0; i < size; ++i) {
      set.add(value(i));
    }
    return set;
  }

  static String key(int i) {
    return "key-" + i;
  }

  static String value(int i) {
    return "value-" + i;
  }

  static String altValue(int i) {
    return "alt-value-" + i;
  }

  static int count(Iterable<?> iterable) {
    return count(iterable.iterator());
  }

  static int count(Iterator<?> iter) {
    int count;
    for (count = 0; iter.hasNext(); ++count) {
      iter.next();
    }
    return count;
  }
  
  static int count(EntryIterator entryIter) {
    int count;
	for ( count = 0; entryIter.next(); ++count ) {
	  // nop  
	}
	return count;
  }

  static final void assertBoolean(boolean expected, TagMap map, String key) {
    assertEquals(expected, map.getBoolean(key));
    assertEquals(expected, map.getBooleanOrDefault(key, !expected));
  }

  static final void assertInt(int expected, TagMap map, String key) {
    assertEquals(expected, map.getInt(key));
    assertEquals(expected, map.getIntOrDefault(key, Integer.MAX_VALUE));
  }

  static final void assertLong(long expected, TagMap map, String key) {
    assertEquals(expected, map.getLong(key));
    assertEquals(expected, map.getLongOrDefault(key, Long.MAX_VALUE));
  }

  static final void assertFloat(float expected, TagMap map, String key) {
    assertEquals(expected, map.getFloat(key));
    assertEquals(expected, map.getFloatOrDefault(key, Float.MAX_VALUE));
  }

  static final void assertDouble(double expected, TagMap map, String key) {
    assertEquals(expected, map.getDouble(key));
    assertEquals(expected, map.getDoubleOrDefault(key, Double.MAX_VALUE));
  }

  static final void assertString(String expected, TagMap map, String key) {
    assertEquals(expected, map.getString(key));
  }

  static final void assertEntry(String key, String value, TagMap map) {
    TagMap.Entry entry = map.getEntry(key);
    assertNotNull(entry);

    assertEquals(key, entry.tag());
    assertEquals(key, entry.getKey());

    assertEquals(value, entry.objectValue());
    assertTrue(entry.isObject());
    assertEquals(value, entry.getValue());

    assertEquals(value, entry.stringValue());

    assertTrue(map.containsKey(key));
    assertTrue(map.keySet().contains(key));

    assertTrue(map.containsValue(value));
    assertTrue(map.values().contains(value));
  }

  static final void assertSize(int size, TagMap map) {
    if (map instanceof OptimizedTagMap) {
      assertEquals(size, ((OptimizedTagMap) map).computeSize());
    }
    assertEquals(size, map.size());

    assertEquals(size, count(map));
    assertEquals(size, map.keySet().size());
    assertEquals(size, map.values().size());
    
    assertEquals(size, count(map.keySet()));
    assertEquals(size, count(map.tagIterator()));
    
    assertEquals(size, count(map.values().iterator()));
    assertEquals(size, count(map.values()));
    
    assertEquals(size, count(map.entryIterator()));
  }

  static void assertEmptiness(TagMapScenario scenario, TagMap map) {
    assertEmptiness(scenario.size() == 0, map);
  }

  static void assertEmptiness(boolean expectEmpty, TagMap map) {
    if (expectEmpty) {
      assertEmpty(map);
    } else {
      assertNotEmpty(map);
    }
  }

  static void assertNotEmpty(TagMap map) {
    if (map instanceof OptimizedTagMap) {
      assertFalse(((OptimizedTagMap) map).checkIfEmpty());
    }
    assertFalse(map.isEmpty());
  }

  static void assertEmpty(TagMap map) {
    if (map instanceof OptimizedTagMap) {
      assertTrue(((OptimizedTagMap) map).checkIfEmpty());
    }
    assertTrue(map.isEmpty());
  }

  static void assertFrozen(TagMap map) {
    IllegalStateException ex = null;
    try {
      map.put("foo", "bar");
    } catch (IllegalStateException e) {
      ex = e;
    }
    assertNotNull(ex);
  }

  static void assertFrozen(Runnable runnable) {
    IllegalStateException ex = null;
    try {
      runnable.run();
    } catch (IllegalStateException e) {
      ex = e;
    }
    assertNotNull(ex);
  }

  static void checkIntegrity(TagMap map) {
    if (map instanceof OptimizedTagMap) {
      OptimizedTagMap optMap = (OptimizedTagMap) map;
      optMap.checkIntegrity();
    }
  }
}
