package datadog.trace.core.util

import datadog.trace.test.util.DDSpecification

class LRUCacheTest extends DDSpecification {
  def "Should eject least recently used element"() {
    when:
    def lruCache = new LRUCache<Integer, String>(5)
    for (int i = 1; i <= 5; i++) {
      lruCache.put(i, String.valueOf(i))
    }
    // now look at 2 values
    lruCache.get(1)
    lruCache.get(3)
    // now insert 2 new values
    lruCache.put(6, "6")
    lruCache.put(7, "7")

    then:
    lruCache.size() == 5
    lruCache.values().containsAll(Arrays.asList("1", "3", "5", "6", "7"))
  }

  def "Should notify listener when ejecting least recently used element"() {
    setup:
    List<Integer> ejected = new ArrayList<>()
    LRUCache.ExpiryListener<Integer, String> listener = { Map.Entry<Integer, String> entry ->
      ejected.add(entry.getKey())
    }
    when:
    def lruCache = new LRUCache<Integer, String>(listener, 10, 0.75f, 5)
    for (int i = 1; i <= 5; i++) {
      lruCache.put(i, String.valueOf(i))
    }
    // now look at 2 values
    lruCache.get(1)
    lruCache.get(3)
    // now insert 2 new values
    lruCache.put(6, "6")
    lruCache.put(7, "7")

    then:
    ejected == [2, 4]
  }
}
