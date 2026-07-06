package datadog.trace.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class LRUCacheTest {

  @Test
  void shouldEjectLeastRecentlyUsedElement() {
    LRUCache<Integer, String> lruCache = new LRUCache<>(5);
    for (int i = 1; i <= 5; i++) {
      lruCache.put(i, String.valueOf(i));
    }
    // now look at 2 values
    lruCache.get(1);
    lruCache.get(3);
    // now insert 2 new values
    lruCache.put(6, "6");
    lruCache.put(7, "7");

    assertEquals(5, lruCache.size());
    assertTrue(lruCache.values().containsAll(Arrays.asList("1", "3", "5", "6", "7")));
  }

  @Test
  void shouldNotifyListenerWhenEjectingLeastRecentlyUsedElement() {
    List<Integer> ejected = new ArrayList<>();
    LRUCache.ExpiryListener<Integer, String> listener = entry -> ejected.add(entry.getKey());

    LRUCache<Integer, String> lruCache = new LRUCache<>(listener, 10, 0.75f, 5);
    for (int i = 1; i <= 5; i++) {
      lruCache.put(i, String.valueOf(i));
    }
    // now look at 2 values
    lruCache.get(1);
    lruCache.get(3);
    // now insert 2 new values
    lruCache.put(6, "6");
    lruCache.put(7, "7");

    assertEquals(Arrays.asList(2, 4), ejected);
  }
}
