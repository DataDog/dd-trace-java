package com.datadog.openfeature.internal.ufc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openfeature.sdk.Value;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FrozenMapTest {
  @ParameterizedTest
  @ValueSource(ints = {0, 1, FrozenMap.MAX_SCANNED, FrozenMap.MAX_SCANNED + 1, 100})
  void behavesLikeTheMapItCopies(final int size) {
    final Map<String, Value> source = new LinkedHashMap<>();
    for (int i = 0; i < size; i++) {
      source.put("k" + i, Value.objectToValue(i));
    }

    final FrozenMap map = FrozenMap.copyOf(source);

    assertEquals(source, map);
    assertEquals(map, source);
    assertEquals(source.hashCode(), map.hashCode());
    assertEquals(size, map.size());
    assertEquals(size == 0, map.isEmpty());
    assertEquals(new ArrayList<>(source.keySet()), new ArrayList<>(map.keySet()));
    assertEquals(new ArrayList<>(source.values()), new ArrayList<>(map.values()));
    assertEquals(new ArrayList<>(source.entrySet()), new ArrayList<>(map.entrySet()));
    for (final Map.Entry<String, Value> entry : source.entrySet()) {
      assertSame(entry.getValue(), map.get(new String(entry.getKey())));
      assertTrue(map.containsKey(entry.getKey()));
      assertTrue(map.keySet().contains(entry.getKey()));
    }
    assertNull(map.get("missing"));
    assertNull(map.get(null));
    assertNull(map.get(42));
    assertFalse(map.containsKey("missing"));
  }

  @ParameterizedTest
  @ValueSource(ints = {3, 50})
  void findsKeysWhoseHashesCollide(final int filler) {
    // "Aa" and "BB" share a String hash.
    final Map<String, Value> source = new LinkedHashMap<>();
    for (int i = 0; i < filler; i++) {
      source.put("f" + i, Value.objectToValue(i));
    }
    source.put("Aa", Value.objectToValue("a"));
    source.put("BB", Value.objectToValue("b"));

    final FrozenMap map = FrozenMap.copyOf(source);

    assertEquals("a", map.get("Aa").asString());
    assertEquals("b", map.get("BB").asString());
  }

  @org.junit.jupiter.api.Test
  void isReadOnlyAndKeepsNoViews() {
    final FrozenMap map = FrozenMap.copyOf(Collections.singletonMap("k", Value.objectToValue(1)));

    assertThrows(UnsupportedOperationException.class, () -> map.put("x", new Value()));
    assertThrows(UnsupportedOperationException.class, () -> map.remove("k"));
    final Iterator<String> keys = map.keySet().iterator();
    keys.next();
    assertThrows(UnsupportedOperationException.class, keys::remove);
    assertThrows(NoSuchElementException.class, keys::next);
    assertFalse(map.keySet() == map.keySet(), "views are not cached");
  }
}
