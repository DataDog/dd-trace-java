package com.datadog.appsec.event.data;

import static java.util.Collections.singleton;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CaseInsensitiveMapTest {

  @Test
  void getAndContainsKey() {
    CaseInsensitiveMap<String> map = new CaseInsensitiveMap<>(singletonMap("FOO", "bar"));

    assertTrue(map.containsKey("fOo"));
    assertEquals("bar", map.get("fOo"));
  }

  @Test
  void size() {
    // when
    CaseInsensitiveMap<String> map = new CaseInsensitiveMap<>();

    // then
    assertTrue(map.isEmpty());
    assertEquals(0, map.size());

    // when
    map = new CaseInsensitiveMap<>(singletonMap("a", "b"));

    // then
    assertFalse(map.isEmpty());
    assertEquals(1, map.size());
  }

  @Test
  void containsValue() {
    // when
    CaseInsensitiveMap<String> map = new CaseInsensitiveMap<>(singletonMap("a", "b"));

    // then
    assertTrue(map.containsValue("b"));
    assertFalse(map.containsValue("c"));
  }

  @Test
  void putAndPutAll() {
    CaseInsensitiveMap<String> map = new CaseInsensitiveMap<>();

    // when
    map.put("A", "b");

    // then
    assertTrue(map.containsKey("a"));

    // when
    Map<String, String> entries = new HashMap<>();
    entries.put("B", "x");
    entries.put("C", "y");
    map.putAll(entries);

    // then
    assertTrue(map.containsKey("b"));
    assertTrue(map.containsKey("c"));
  }

  @Test
  void clearAndRemove() {
    // when
    CaseInsensitiveMap<String> map = new CaseInsensitiveMap<>(singletonMap("A", "a"));
    map.clear();

    // then
    assertEquals(0, map.size());

    // when
    map = new CaseInsensitiveMap<>(singletonMap("A", "b"));
    map.remove("a");

    // then
    assertEquals(0, map.size());
  }

  @Test
  void keySetValuesAndEntrySet() {
    // when
    CaseInsensitiveMap<String> map = new CaseInsensitiveMap<>(singletonMap("A", "B"));

    // then
    Collection<String> values = map.values();
    assertEquals(1, values.size());
    assertEquals("B", values.iterator().next());
    assertEquals(singleton("a"), map.keySet());
    Set<Map.Entry<String, String>> entrySet = map.entrySet();
    assertEquals(1, entrySet.size());
    Map.Entry<String, String> entry = entrySet.iterator().next();
    assertEquals("a", entry.getKey());
    assertEquals("B", entry.getValue());
  }
}
