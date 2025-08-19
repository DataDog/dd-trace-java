package datadog.trace.api;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

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
  @EnumSource(TagMapType.class)
  public void map_put(TagMapType mapType) {
    TagMap map = mapType.create();

    Object prev = map.put("foo", "bar");
    assertNull(prev);

    assertEntry("foo", "bar", map);

    assertSize(1, map);
    assertNotEmpty(map);
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  public void booleanEntry(TagMapType mapType) {
    boolean first = false;
    boolean second = true;

    TagMap map = mapType.create();
    map.set("bool", first);

    TagMap.Entry firstEntry = map.getEntry("bool");
    if (map.isOptimized()) {
      assertEquals(TagMap.Entry.BOOLEAN, firstEntry.rawType);
    }

    assertEquals(first, firstEntry.booleanValue());
    assertEquals(first, map.getBoolean("bool"));

    TagMap.Entry priorEntry = map.getAndSet("bool", second);
    if (map.isOptimized()) {
      assertSame(priorEntry, firstEntry);
    }
    assertEquals(first, priorEntry.booleanValue());

    TagMap.Entry newEntry = map.getEntry("bool");
    assertEquals(second, newEntry.booleanValue());

    assertFalse(map.getBoolean("unset"));
    assertTrue(map.getBooleanOrDefault("unset", true));
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

    assertFalse(map.getBoolean("int"));
    assertFalse(map.getBoolean("intObj"));
    assertFalse(map.getBoolean("long"));
    assertFalse(map.getBoolean("longObj"));
    assertFalse(map.getBoolean("float"));
    assertFalse(map.getBoolean("floatObj"));
    assertFalse(map.getBoolean("double"));
    assertFalse(map.getBoolean("doubleObj"));
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

    assertTrue(map.getBoolean("int"));
    assertTrue(map.getBoolean("intObj"));
    assertTrue(map.getBoolean("long"));
    assertTrue(map.getBoolean("longObj"));
    assertTrue(map.getBoolean("float"));
    assertTrue(map.getBoolean("floatObj"));
    assertTrue(map.getBoolean("double"));
    assertTrue(map.getBoolean("doubleObj"));
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

    assertTrue(map.getBoolean("obj"));
    assertTrue(map.getBoolean("trueStr"));
    assertTrue(map.getBoolean("falseStr"));
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  public void booleanToNumericCoercion_true(TagMapType mapType) {
    TagMap map = TagMap.ledger().set("true", true).build(mapType.factory);

    assertEquals(1, map.getInt("true"));
    assertEquals(1L, map.getLong("true"));
    assertEquals(1F, map.getFloat("true"));
    assertEquals(1D, map.getDouble("true"));
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  public void booleanToNumericCoercion_false(TagMapType mapType) {
    TagMap map = TagMap.ledger().set("false", false).build(mapType.factory);

    assertEquals(0, map.getInt("false"));
    assertEquals(0L, map.getLong("false"));
    assertEquals(0F, map.getFloat("false"));
    assertEquals(0D, map.getDouble("false"));
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  public void emptyToPrimitiveCoercion(TagMapType mapType) {
    TagMap map = mapType.empty();

    assertFalse(map.getBoolean("dne"));
    assertEquals(0, map.getInt("dne"));
    assertEquals(0L, map.getLong("dne"));
    assertEquals(0F, map.getFloat("dne"));
    assertEquals(0D, map.getDouble("dne"));
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  public void intEntry(TagMapType mapType) {
    int first = 3142;
    int second = 2718;

    TagMap map = mapType.create();
    map.set("int", first);

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

    assertEquals(0, map.getInt("unset"));
    assertEquals(21, map.getIntOrDefault("unset", 21));
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  public void longEntry(TagMapType mapType) {
    long first = 3142L;
    long second = 2718L;

    TagMap map = mapType.create();
    map.set("long", first);

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

    assertEquals(0L, map.getLong("unset"));
    assertEquals(21L, map.getLongOrDefault("unset", 21L));
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  public void floatEntry(TagMapType mapType) {
    float first = 3.14F;
    float second = 2.718F;

    TagMap map = mapType.create();
    map.set("float", first);

    TagMap.Entry firstEntry = map.getEntry("float");
    if (map.isOptimized()) {
      assertEquals(TagMap.Entry.FLOAT, firstEntry.rawType);
    }

    assertEquals(first, firstEntry.floatValue());
    assertEquals(first, map.getFloat("float"));

    TagMap.Entry priorEntry = map.getAndSet("float", second);
    if (map.isOptimized()) {
      assertSame(priorEntry, firstEntry);
    }
    assertEquals(first, priorEntry.floatValue());

    TagMap.Entry newEntry = map.getEntry("float");
    assertEquals(second, newEntry.floatValue());

    assertEquals(0F, map.getFloat("unset"));
    assertEquals(2.718F, map.getFloatOrDefault("unset", 2.718F));
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  public void doubleEntry(TagMapType mapType) {
    double first = Math.PI;
    double second = Math.E;

    TagMap map = mapType.create();
    map.set("double", Math.PI);

    TagMap.Entry firstEntry = map.getEntry("double");
    if (map.isOptimized()) {
      assertEquals(TagMap.Entry.DOUBLE, firstEntry.rawType);
    }

    assertEquals(first, firstEntry.doubleValue());
    assertEquals(first, map.getDouble("double"));

    TagMap.Entry priorEntry = map.getAndSet("double", second);
    if (map.isOptimized()) {
      assertSame(priorEntry, firstEntry);
    }
    assertEquals(first, priorEntry.doubleValue());

    TagMap.Entry newEntry = map.getEntry("double");
    assertEquals(second, newEntry.doubleValue());

    assertEquals(0D, map.getDouble("unset"));
    assertEquals(2.718D, map.getDoubleOrDefault("unset", 2.718D));
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  public void empty(TagMapType mapType) {
    TagMap empty = mapType.empty();
    assertFrozen(empty);

    assertNull(empty.getEntry("foo"));
    assertSize(0, empty);
    assertEmpty(empty);
  }

  @ParameterizedTest
  @EnumSource(TagMapTypePair.class)
  public void putAll_empty(TagMapTypePair mapTypePair) {
    // TagMap.EMPTY breaks the rules and uses a different size bucket array
    // This test is just to verify that the commonly use putAll still works with EMPTY
    TagMap newMap = mapTypePair.firstType.create();
    newMap.putAll(mapTypePair.secondType.empty());

    assertSize(0, newMap);
    assertEmpty(newMap);
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  public void clear(TagMapType mapType) {
    int size = randomSize();

    TagMap map = createTagMap(mapType, size);
    assertSize(size, map);
    assertNotEmpty(map);

    map.clear();
    assertSize(0, map);
    assertEmpty(map);
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  public void map_put_replacement(TagMapType mapType) {
    TagMap map = mapType.create();
    Object prev1 = map.put("foo", "bar");
    assertNull(prev1);

    assertEntry("foo", "bar", map);
    assertSize(1, map);
    assertNotEmpty(map);

    Object prev2 = map.put("foo", "baz");
    assertSize(1, map);
    assertEquals("bar", prev2);

    assertEntry("foo", "baz", map);
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  public void map_remove(TagMapType mapType) {
    TagMap map = mapType.create();

    Object prev1 = map.remove((Object) "foo");
    assertNull(prev1);

    map.put("foo", "bar");
    assertEntry("foo", "bar", map);
    assertSize(1, map);
    assertNotEmpty(map);

    Object prev2 = map.remove((Object) "foo");
    assertEquals("bar", prev2);
    assertSize(0, map);
    assertEmpty(map);
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  public void freeze(TagMapType mapType) {
    TagMap map = mapType.create();
    map.put("foo", "bar");

    assertEntry("foo", "bar", map);

    map.freeze();

    assertFrozen(
        () -> {
          map.remove("foo");
        });

    assertEntry("foo", "bar", map);
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  public void emptyMap(TagMapType mapType) {
    TagMap map = mapType.empty();

    assertSize(0, map);
    assertEmpty(map);

    assertFrozen(map);
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  public void putMany(TagMapType mapType) {
    int size = randomSize();
    TagMap map = createTagMap(mapType, size);

    for (int i = 0; i < size; ++i) {
      assertEntry(key(i), value(i), map);
    }

    assertNotEmpty(map);
    assertSize(size, map);
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  public void copyMany(TagMapType mapType) {
    int size = randomSize();
    TagMap orig = createTagMap(mapType, size);
    assertSize(size, orig);

    TagMap copy = orig.copy();
    orig.clear(); // doing this to make sure that copied isn't modified

    for (int i = 0; i < size; ++i) {
      assertEntry(key(i), value(i), copy);
    }
    assertSize(size, copy);
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
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  public void iterator(TagMapType mapType) {
    int size = randomSize();
    TagMap map = createTagMap(mapType, size);

    Set<String> keys = new HashSet<>();
    for (TagMap.Entry entry : map) {
      // makes sure that each key is visited once and only once
      assertTrue(keys.add(entry.tag()));
    }

    for (int i = 0; i < size; ++i) {
      // make sure the key was present
      assertTrue(keys.remove(key(i)));
    }

    // no extraneous keys
    assertTrue(keys.isEmpty());
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
  }

  @ParameterizedTest
  @EnumSource(TagMapType.class)
  public void _toString(TagMapType mapType) {
    int size = 4;
    TagMap map = createTagMap(mapType, size);
    assertEquals("{key-1=value-1, key-0=value-0, key-3=value-3, key-2=value-2}", map.toString());
  }

  static int randomSize() {
    return ThreadLocalRandom.current().nextInt(1, MANY_SIZE);
  }

  static TagMap createTagMap(TagMapType mapType) {
    return createTagMap(mapType, randomSize());
  }

  static TagMap createTagMap(TagMapType mapType, int size) {
    TagMap map = mapType.create();
    for (int i = 0; i < size; ++i) {
      map.set(key(i), value(i));
    }
    return map;
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

  static void assertEntry(String key, String value, TagMap map) {
    TagMap.Entry entry = map.getEntry(key);
    assertNotNull(entry);

    assertEquals(key, entry.tag());
    assertEquals(key, entry.getKey());

    assertEquals(value, entry.objectValue());
    assertTrue(entry.isObject());
    assertEquals(value, entry.getValue());

    assertEquals(value, entry.stringValue());

    assertTrue(map.containsKey(key));
    assertTrue(map.containsKey(key));

    assertTrue(map.containsValue(value));
    assertTrue(map.containsValue(value));
  }

  static void assertSize(int size, TagMap map) {
    if (map instanceof OptimizedTagMap) {
      assertEquals(size, ((OptimizedTagMap) map).computeSize());
    }
    assertEquals(size, map.size());

    assertEquals(size, count(map));
    assertEquals(size, map.size());
    assertEquals(size, map.size());
    assertEquals(size, count(map.keySet()));
    assertEquals(size, count(map.values()));
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
}
