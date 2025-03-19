package datadog.trace.api;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Iterator;
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
    
    assertFrozen(() -> {
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
    
    for ( int i = 0; i < size; ++i ) {
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
    
    for ( int i = 0; i < size; ++i ) {
      assertEntry(key(i), value(i), copy);
    }
  }
  
  @Test
  public void replaceALot() {
    int size = randomSize();
    TagMap map = createTagMap(size);
    
    for ( int i = 0; i < size; ++i ) {
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
    for ( int i = size - 1; i >= 0 ; --i ) {
      dest.set(key(i), altValue(i));
    }
    
    // This should clobber all the values in dest
    dest.putAll(orig);
    
    // assertSize(size,  dest);
    for ( int i = 0; i < size; ++i ) {
      assertEntry(key(i), value(i), dest);
    }
  }
  
  @Test
  public void removeMany() {
    int size = randomSize();
    TagMap map = createTagMap(size);
    
    for ( int i = 0; i < size; ++i ) {
      assertEntry(key(i), value(i), map);
    }
    
    assertNotEmpty(map);
    assertSize(size, map);
    
    for ( int i = 0; i < size; ++i ) {
      Object removedValue = map.remove(key(i));
      assertEquals(value(i), removedValue);
      
      // not doing exhaustive size checks
      assertEquals(size - i - 1, map.computeSize());
    }
    
    assertEmpty(map);
  }
  
  @Test
  public void iterator() {
    int size = randomSize();
    TagMap map = createTagMap(size);
    
    Set<String> keys = new HashSet<>();
    for ( TagMap.Entry entry: map ) {
      // makes sure that each key is visited once and only once
      assertTrue(keys.add(entry.tag()));
    }
    
    for ( int i = 0; i < size; ++i ) {
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
    
    for ( int i = 0; i < size; ++i ) {
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
    
    for ( int i = 0; i < size; ++i ) {
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
    map.forEach(keys, "hi",  (k, msg, entry) -> keys.add(entry.tag()));
    
    for ( int i = 0; i < size; ++i ) {
      // make sure the key was present
      assertTrue(keys.remove(key(i)));
    }
    
    // no extraneous keys
    assertTrue(keys.isEmpty());
  }
  
  static final int randomSize() {
    return ThreadLocalRandom.current().nextInt(MANY_SIZE);
  }
  
  static final TagMap createTagMap() {
    return createTagMap(randomSize());
  }
  
  static final TagMap createTagMap(int size) {
    TagMap map = new TagMap();
    for ( int i = 0; i < size; ++i ) {
      map.set(key(i), value(i));
    }
    return map;
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
    for ( count = 0; iter.hasNext(); ++count ) {
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
    } catch ( IllegalStateException e ) {
      ex = e;
    }
    assertNotNull(ex);
  }
  
  static final void assertFrozen(Runnable runnable) {
    IllegalStateException ex = null;
    try {
      runnable.run();
    } catch ( IllegalStateException e ) {
      ex = e;
    }
    assertNotNull(ex);
  }
}
