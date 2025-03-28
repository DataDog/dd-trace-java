package datadog.trace.api;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;

public class TagMapTest {
  // size is chosen to make sure to stress all types of collisions in the Map
  static final int MANY_SIZE = 256;

  @Test
  public void map_put() {
    TagMap map = new TagMap();

    Object prev = map.put("foo", "bar");
    assertNull(prev);

    assertEntry("foo", "bar", map);

    assertSize(1, map);
    assertNotEmpty(map);
  }

  @Test
  public void booleanEntry() {
    TagMap map = new TagMap();
    map.set("bool", false);

    TagMap.Entry entry = map.getEntry("bool");
    assertEquals(TagMap.Entry.BOOLEAN, entry.rawType);

    assertEquals(false, entry.booleanValue());
    assertEquals(false, map.getBoolean("bool"));
  }

  @Test
  public void intEntry() {
    TagMap map = new TagMap();
    map.set("int", 42);

    TagMap.Entry entry = map.getEntry("int");
    assertEquals(TagMap.Entry.INT, entry.rawType);

    assertEquals(42, entry.intValue());
    assertEquals(42, map.getInt("int"));
  }

  @Test
  public void longEntry() {
    TagMap map = new TagMap();
    map.set("long", 42L);

    TagMap.Entry entry = map.getEntry("long");
    assertEquals(TagMap.Entry.LONG, entry.rawType);

    assertEquals(42L, entry.longValue());
    assertEquals(42L, map.getLong("long"));
  }

  @Test
  public void floatEntry() {
    TagMap map = new TagMap();
    map.set("float", 3.14F);

    TagMap.Entry entry = map.getEntry("float");
    assertEquals(TagMap.Entry.FLOAT, entry.rawType);

    assertEquals(3.14F, entry.floatValue());
    assertEquals(3.14F, map.getFloat("float"));
  }

  @Test
  public void doubleEntry() {
    TagMap map = new TagMap();
    map.set("double", Math.PI);

    TagMap.Entry entry = map.getEntry("double");
    assertEquals(TagMap.Entry.DOUBLE, entry.rawType);

    assertEquals(Math.PI, entry.doubleValue());
    assertEquals(Math.PI, map.getDouble("double"));
  }

  @Test
  public void empty() {
    TagMap empty = TagMap.EMPTY;
    assertFrozen(empty);

    assertNull(empty.getEntry("foo"));
    assertSize(0, empty);
    assertEmpty(empty);
  }

  @Test
  public void clear() {
    int size = randomSize();

    TagMap map = createTagMap(size);
    assertSize(size, map);
    assertNotEmpty(map);

    map.clear();
    assertSize(0, map);
    assertEmpty(map);
  }

  @Test
  public void map_put_replacement() {
    TagMap map = new TagMap();
    Object prev1 = map.put("foo", "bar");
    assertNull(prev1);

    assertEntry("foo", "bar", map);
    assertSize(1, map);
    assertNotEmpty(map);

    Object prev2 = map.put("foo", "baz");
    assertEquals("bar", prev2);

    assertEntry("foo", "baz", map);
  }

  @Test
  public void map_remove() {
    TagMap map = new TagMap();

    Object prev1 = map.remove("foo");
    assertNull(prev1);

    map.put("foo", "bar");
    assertEntry("foo", "bar", map);
    assertSize(1, map);
    assertNotEmpty(map);

    Object prev2 = map.remove("foo");
    assertEquals("bar", prev2);
    assertSize(0, map);
    assertEmpty(map);
  }

  @Test
  public void freeze() {
    TagMap map = new TagMap();
    map.put("foo", "bar");

    assertEntry("foo", "bar", map);

    map.freeze();

    assertFrozen(
        () -> {
          map.remove("foo");
        });

    assertEntry("foo", "bar", map);
  }

  @Test
  public void emptyMap() {
    TagMap map = TagMap.EMPTY;

    assertSize(0, map);
    assertEmpty(map);

    assertFrozen(map);
  }

  @Test
  public void putMany() {
    int size = randomSize();
    TagMap map = createTagMap(size);

    for (int i = 0; i < size; ++i) {
      assertEntry(key(i), value(i), map);
    }

    assertNotEmpty(map);
    assertSize(size, map);
  }

  @Test
  public void cloneMany() {
    int size = randomSize();
    TagMap orig = createTagMap(size);

    TagMap copy = orig.copy();
    orig.clear(); // doing this to make sure that copied isn't modified

    for (int i = 0; i < size; ++i) {
      assertEntry(key(i), value(i), copy);
    }
  }

  @Test
  public void replaceALot() {
    int size = randomSize();
    TagMap map = createTagMap(size);

    for (int i = 0; i < size; ++i) {
      int index = ThreadLocalRandom.current().nextInt(size);

      map.put(key(index), altValue(index));
      assertEquals(altValue(index), map.get(key(index)));
    }
  }

  @Test
  public void shareEntry() {
    TagMap orig = new TagMap();
    orig.set("foo", "bar");

    TagMap dest = new TagMap();
    dest.putEntry(orig.getEntry("foo"));

    assertSame(orig.getEntry("foo"), dest.getEntry("foo"));
  }

  @Test
  public void putAll() {
    int size = 67;
    TagMap orig = createTagMap(size);

    TagMap dest = new TagMap();
    for (int i = size - 1; i >= 0; --i) {
      dest.set(key(i), altValue(i));
    }

    // This should clobber all the values in dest
    dest.putAll(orig);

    // assertSize(size,  dest);
    for (int i = 0; i < size; ++i) {
      assertEntry(key(i), value(i), dest);
    }
  }

  @Test
  public void removeMany() {
    int size = randomSize();
    TagMap map = createTagMap(size);

    for (int i = 0; i < size; ++i) {
      assertEntry(key(i), value(i), map);
    }

    assertNotEmpty(map);
    assertSize(size, map);

    for (int i = 0; i < size; ++i) {
      Object removedValue = map.remove(key(i));
      assertEquals(value(i), removedValue);

      // not doing exhaustive size checks
      assertEquals(size - i - 1, map.computeSize());
    }

    assertEmpty(map);
  }

  @Test
  public void fillMap() {
    int size = randomSize();
    TagMap map = new TagMap();
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

  @Test
  public void fillStringMap() {
    int size = randomSize();
    TagMap map = new TagMap();
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

  @Test
  public void iterator() {
    int size = randomSize();
    TagMap map = createTagMap(size);

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

  @Test
  public void forEachConsumer() {
    int size = randomSize();
    TagMap map = createTagMap(size);

    Set<String> keys = new HashSet<>(size);
    map.forEach((entry) -> keys.add(entry.tag()));

    for (int i = 0; i < size; ++i) {
      // make sure the key was present
      assertTrue(keys.remove(key(i)));
    }

    // no extraneous keys
    assertTrue(keys.isEmpty());
  }

  @Test
  public void forEachBiConsumer() {
    int size = randomSize();
    TagMap map = createTagMap(size);

    Set<String> keys = new HashSet<>(size);
    map.forEach(keys, (k, entry) -> k.add(entry.tag()));

    for (int i = 0; i < size; ++i) {
      // make sure the key was present
      assertTrue(keys.remove(key(i)));
    }

    // no extraneous keys
    assertTrue(keys.isEmpty());
  }

  @Test
  public void forEachTriConsumer() {
    int size = randomSize();
    TagMap map = createTagMap(size);

    Set<String> keys = new HashSet<>(size);
    map.forEach(keys, "hi", (k, msg, entry) -> keys.add(entry.tag()));

    for (int i = 0; i < size; ++i) {
      // make sure the key was present
      assertTrue(keys.remove(key(i)));
    }

    // no extraneous keys
    assertTrue(keys.isEmpty());
  }

  @Test
  public void entrySet() {
    int size = randomSize();
    TagMap map = createTagMap(size);

    Set<Map.Entry<String, Object>> actualEntries = map.entrySet();
    assertEquals(size, actualEntries.size());
    assertFalse(actualEntries.isEmpty());

    Set<String> expectedKeys = expectedKeys(size);
    for (Map.Entry<String, Object> entry : actualEntries) {
      assertTrue(expectedKeys.remove(entry.getKey()));
    }
    assertTrue(expectedKeys.isEmpty());
  }

  @Test
  public void keySet() {
    int size = randomSize();
    TagMap map = createTagMap(size);

    Set<String> actualKeys = map.keySet();
    assertEquals(size, actualKeys.size());
    assertFalse(actualKeys.isEmpty());

    Set<String> expectedKeys = expectedKeys(size);
    for (String key : actualKeys) {
      assertTrue(expectedKeys.remove(key));
    }
    assertTrue(expectedKeys.isEmpty());
  }

  @Test
  public void values() {
    int size = randomSize();
    TagMap map = createTagMap(size);

    Collection<Object> actualValues = map.values();
    assertEquals(size, actualValues.size());
    assertFalse(actualValues.isEmpty());

    Set<String> expectedValues = expectedValues(size);
    for (Object value : map.values()) {
      assertTrue(expectedValues.remove(value));
    }
    assertTrue(expectedValues.isEmpty());
  }

  static final int randomSize() {
    return ThreadLocalRandom.current().nextInt(MANY_SIZE);
  }

  static final TagMap createTagMap() {
    return createTagMap(randomSize());
  }

  static final TagMap createTagMap(int size) {
    TagMap map = new TagMap();
    for (int i = 0; i < size; ++i) {
      map.set(key(i), value(i));
    }
    return map;
  }

  static final Set<String> expectedKeys(int size) {
    Set<String> set = new HashSet<String>(size);
    for (int i = 0; i < size; ++i) {
      set.add(key(i));
    }
    return set;
  }

  static final Set<String> expectedValues(int size) {
    Set<String> set = new HashSet<String>(size);
    for (int i = 0; i < size; ++i) {
      set.add(value(i));
    }
    return set;
  }

  static final String key(int i) {
    return "key-" + i;
  }

  static final String value(int i) {
    return "value-" + i;
  }

  static final String altValue(int i) {
    return "alt-value-" + i;
  }

  static final int count(Iterable<?> iterable) {
    return count(iterable.iterator());
  }

  static final int count(Iterator<?> iter) {
    int count;
    for (count = 0; iter.hasNext(); ++count) {
      iter.next();
    }
    return count;
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
    assertEquals(size, map.computeSize());
    assertEquals(size, map.size());

    assertEquals(size, count(map));
    assertEquals(size, map.keySet().size());
    assertEquals(size, map.values().size());
    assertEquals(size, count(map.keySet()));
    assertEquals(size, count(map.values()));
  }

  static final void assertNotEmpty(TagMap map) {
    assertFalse(map.checkIfEmpty());
    assertFalse(map.isEmpty());
  }

  static final void assertEmpty(TagMap map) {
    assertTrue(map.checkIfEmpty());
    assertTrue(map.isEmpty());
  }

  static final void assertFrozen(TagMap map) {
    IllegalStateException ex = null;
    try {
      map.put("foo", "bar");
    } catch (IllegalStateException e) {
      ex = e;
    }
    assertNotNull(ex);
  }

  static final void assertFrozen(Runnable runnable) {
    IllegalStateException ex = null;
    try {
      runnable.run();
    } catch (IllegalStateException e) {
      ex = e;
    }
    assertNotNull(ex);
  }
}
