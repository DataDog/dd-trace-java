package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FrozenMapTest {
  @ParameterizedTest
  @ValueSource(ints = {0, 1, FrozenMap.MAX_SCANNED, FrozenMap.MAX_SCANNED + 1, 100})
  void behavesLikeTheMapItCopies(final int size) {
    final Map<String, Integer> source = new LinkedHashMap<>();
    for (int i = 0; i < size; i++) {
      source.put("k" + i, i);
    }

    final FrozenMap<String, Integer> map = FrozenMap.copyOf(source);

    assertEquals(source, map);
    assertEquals(map, source);
    assertEquals(source.hashCode(), map.hashCode());
    assertEquals(source.toString(), map.toString());
    assertEquals(size, map.size());
    assertEquals(size == 0, map.isEmpty());
    assertEquals(new ArrayList<>(source.keySet()), new ArrayList<>(map.keySet()));
    assertEquals(new ArrayList<>(source.values()), new ArrayList<>(map.values()));
    assertEquals(new ArrayList<>(source.entrySet()), new ArrayList<>(map.entrySet()));
    for (final Map.Entry<String, Integer> entry : source.entrySet()) {
      assertSame(entry.getValue(), map.get(new String(entry.getKey())));
      assertTrue(map.containsKey(entry.getKey()));
      assertTrue(map.keySet().contains(entry.getKey()));
    }
    assertNull(map.get("missing"));
    assertNull(map.get(null));
    assertNull(map.get(42));
    assertFalse(map.containsKey("missing"));
  }

  @Test
  void keepsTheSourceIterationOrder() {
    final Map<String, Integer> source = new HashMap<>();
    for (int i = 0; i < 50; i++) {
      source.put("key-" + i, i);
    }

    assertEquals(
        new ArrayList<>(source.keySet()), new ArrayList<>(FrozenMap.copyOf(source).keySet()));
  }

  @ParameterizedTest
  @ValueSource(ints = {3, 50})
  void findsKeysWhoseHashesCollide(final int filler) {
    // "Aa" and "BB" share a String hash.
    final Map<String, String> source = new LinkedHashMap<>();
    for (int i = 0; i < filler; i++) {
      source.put("f" + i, "v" + i);
    }
    source.put("Aa", "a");
    source.put("BB", "b");

    final FrozenMap<String, String> map = FrozenMap.copyOf(source);

    assertEquals("a", map.get("Aa"));
    assertEquals("b", map.get("BB"));
  }

  @Test
  void forEachVisitsEveryEntryInOrder() {
    final Map<String, Integer> source = new LinkedHashMap<>();
    source.put("b", 2);
    source.put("a", 1);
    final List<String> visited = new ArrayList<>();

    FrozenMap.copyOf(source).forEach((k, v) -> visited.add(k + "=" + v));

    assertEquals(java.util.Arrays.asList("b=2", "a=1"), visited);
  }

  @Test
  void allowsNullValuesButNotNullKeys() {
    final Map<String, Integer> withNullValue = new HashMap<>();
    withNullValue.put("k", null);
    final FrozenMap<String, Integer> map = FrozenMap.copyOf(withNullValue);
    assertTrue(map.containsKey("k"));
    assertNull(map.get("k"));

    final Map<String, Integer> withNullKey = new HashMap<>();
    withNullKey.put(null, 1);
    assertThrows(NullPointerException.class, () -> FrozenMap.copyOf(withNullKey));
  }

  @Test
  void isReadOnlyAndKeepsNoViews() {
    final FrozenMap<String, Integer> map = FrozenMap.copyOf(Collections.singletonMap("k", 1));

    assertThrows(UnsupportedOperationException.class, () -> map.put("x", 2));
    assertThrows(UnsupportedOperationException.class, () -> map.remove("k"));
    final Iterator<String> keys = map.keySet().iterator();
    keys.next();
    assertThrows(UnsupportedOperationException.class, keys::remove);
    assertThrows(NoSuchElementException.class, keys::next);
    assertNotSame(map.keySet(), map.keySet(), "views are not cached");
    assertNotSame(map.entrySet(), map.entrySet());
    assertNotSame(map.values(), map.values());
  }

  @Test
  void sharesOneEmptyMap() {
    assertSame(FrozenMap.copyOf(Collections.emptyMap()), FrozenMap.copyOf(new HashMap<>()));
  }
}
