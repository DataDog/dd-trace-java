package com.datadog.featureflag;

import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.featureflag.exposure.Allocation;
import datadog.trace.api.featureflag.exposure.ExposureEvent;
import datadog.trace.api.featureflag.exposure.Flag;
import datadog.trace.api.featureflag.exposure.Subject;
import datadog.trace.api.featureflag.exposure.Variant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LRUExposureCacheTest {

  @Test
  void testAddingElements() {
    LRUExposureCache cache = new LRUExposureCache(5);
    ExposureEvent event = createEvent("flag", "subject", "variant", "allocation");

    boolean added = cache.add(event);

    assertTrue(added);
    assertEquals(1, cache.size());
  }

  @Test
  void testAddingDuplicateEventsReturnsFalse() {
    LRUExposureCache cache = new LRUExposureCache(5);
    ExposureEvent event = createEvent("flag", "subject", "variant", "allocation");

    cache.add(event);
    boolean duplicateAdded = cache.add(event);

    assertFalse(duplicateAdded);
    assertEquals(1, cache.size());
  }

  @Test
  void testAddingEventsWithSameKeyButDifferentDetailsUpdatesCache() {
    LRUExposureCache cache = new LRUExposureCache(5);
    ExposureEvent event1 = createEvent("flag", "subject", "variant1", "allocation1");
    ExposureEvent event2 = createEvent("flag", "subject", "variant2", "allocation2");
    ExposureCache.Key key = new ExposureCache.Key(event1);

    boolean added1 = cache.add(event1);
    boolean added2 = cache.add(event2);
    ExposureCache.Value retrieved = cache.get(key);

    assertTrue(added1);
    assertTrue(added2);
    assertEquals(1, cache.size());
    assertEquals("variant2", retrieved.variant);
    assertEquals("allocation2", retrieved.allocation);
  }

  @Test
  void testLruEvictionWhenCapacityExceeded() {
    LRUExposureCache cache = new LRUExposureCache(2);
    ExposureEvent event1 = createEvent("flag1", "subject1", "variant1", "allocation1");
    ExposureEvent event2 = createEvent("flag2", "subject2", "variant2", "allocation2");
    ExposureEvent event3 = createEvent("flag3", "subject3", "variant3", "allocation3");
    ExposureCache.Key key1 = new ExposureCache.Key(event1);
    ExposureCache.Key key3 = new ExposureCache.Key(event3);

    cache.add(event1);
    cache.add(event2);
    cache.add(event3);

    assertEquals(2, cache.size());
    assertNull(cache.get(key1)); // event1 should be evicted
    assertNotNull(cache.get(key3)); // event3 should be present
    assertEquals("variant3", cache.get(key3).variant);
    assertEquals("allocation3", cache.get(key3).allocation);
  }

  @Test
  void testSingleCapacityCache() {
    LRUExposureCache cache = new LRUExposureCache(1);
    ExposureEvent event1 = createEvent("flag1", "subject1", "variant1", "allocation1");
    ExposureEvent event2 = createEvent("flag2", "subject2", "variant2", "allocation2");

    cache.add(event1);
    cache.add(event2);

    assertEquals(1, cache.size());
  }

  @Test
  void testZeroCapacityCache() {
    LRUExposureCache cache = new LRUExposureCache(0);
    ExposureEvent event = createEvent("flag", "subject", "variant", "allocation");

    boolean added = cache.add(event);

    assertTrue(added);
    assertEquals(0, cache.size());
  }

  @Test
  void testEmptyCacheSize() {
    LRUExposureCache cache = new LRUExposureCache(5);

    assertEquals(0, cache.size());
  }

  @Test
  void testMultipleAdditionsWithSameFlagDifferentSubjects() {
    LRUExposureCache cache = new LRUExposureCache(10);
    List<ExposureEvent> events = new ArrayList<>();
    for (int index = 0; index < 5; index++) {
      events.add(createEvent("flag", "subject" + index, "variant", "allocation"));
    }

    for (ExposureEvent event : events) {
      assertTrue(cache.add(event));
    }

    assertEquals(5, cache.size());
  }

  @Test
  void testMultipleAdditionsWithSameSubjectDifferentFlags() {
    LRUExposureCache cache = new LRUExposureCache(10);
    List<ExposureEvent> events = new ArrayList<>();
    for (int index = 0; index < 5; index++) {
      events.add(createEvent("flag" + index, "subject", "variant", "allocation"));
    }

    for (ExposureEvent event : events) {
      assertTrue(cache.add(event));
    }

    assertEquals(5, cache.size());
  }

  @Test
  void testKeyEqualityWithNullValues() {
    LRUExposureCache cache = new LRUExposureCache(5);
    ExposureEvent event1 =
        new ExposureEvent(
            System.currentTimeMillis(),
            new Allocation("allocation"),
            new Flag(null),
            new Variant("variant"),
            new Subject(null, emptyMap()));
    ExposureEvent event2 =
        new ExposureEvent(
            System.currentTimeMillis(),
            new Allocation("allocation"),
            new Flag(null),
            new Variant("variant"),
            new Subject(null, emptyMap()));

    cache.add(event1);
    boolean duplicateAdded = cache.add(event2);

    assertFalse(duplicateAdded);
    assertEquals(1, cache.size());
  }

  @Test
  void testUpdatingExistingKeyMaintainsLruPosition() {
    LRUExposureCache cache = new LRUExposureCache(3);
    ExposureEvent event1 = createEvent("flag1", "subject1", "variant1", "allocation1");
    ExposureEvent event2 = createEvent("flag2", "subject2", "variant2", "allocation2");
    ExposureEvent event3 = createEvent("flag3", "subject3", "variant3", "allocation3");
    ExposureEvent event1Updated = createEvent("flag1", "subject1", "variant2", "allocation2");
    ExposureEvent event4 = createEvent("flag4", "subject4", "variant4", "allocation4");
    ExposureCache.Key key1 = new ExposureCache.Key(event1);
    ExposureCache.Key key2 = new ExposureCache.Key(event2);
    ExposureCache.Key key4 = new ExposureCache.Key(event4);

    cache.add(event1);
    cache.add(event2);
    cache.add(event3);
    cache.add(event1Updated); // Updates event1, moves to most recent
    cache.add(event4); // Should evict event2, not event1

    assertEquals(3, cache.size());
    assertNotNull(cache.get(key1)); // event1 should be updated and present
    assertEquals("variant2", cache.get(key1).variant); // verify it was updated
    assertEquals("allocation2", cache.get(key1).allocation);
    assertNull(cache.get(key2)); // event2 should be evicted
    assertNotNull(cache.get(key4)); // event4 should be present
    assertEquals("variant4", cache.get(key4).variant);
  }

  @Test
  void testDuplicateExposureKeepsSubjectHotInLruOrder() {
    LRUExposureCache cache = new LRUExposureCache(3);
    ExposureEvent event1 = createEvent("flag1", "subject1", "variant1", "allocation1");
    ExposureEvent event2 = createEvent("flag2", "subject2", "variant2", "allocation2");
    ExposureEvent event3 = createEvent("flag3", "subject3", "variant3", "allocation3");
    // same key + same details as event1: will go through the "duplicate" path
    ExposureEvent event1Duplicate = createEvent("flag1", "subject1", "variant1", "allocation1");
    ExposureEvent event4 = createEvent("flag4", "subject4", "variant4", "allocation4");

    ExposureCache.Key key1 = new ExposureCache.Key(event1);
    ExposureCache.Key key2 = new ExposureCache.Key(event2);
    ExposureCache.Key key4 = new ExposureCache.Key(event4);

    // Fill cache
    boolean added1 = cache.add(event1);
    boolean added2 = cache.add(event2);
    boolean added3 = cache.add(event3);

    // Duplicate exposure for subject1: should *not* change size, but *should* bump recency
    boolean duplicateAdded = cache.add(event1Duplicate);

    // Now push over capacity: the least recently used *non-hot* entry (event2) should be evicted
    boolean added4 = cache.add(event4);

    assertTrue(added1);
    assertTrue(added2);
    assertTrue(added3);
    assertFalse(duplicateAdded); // dedup correctly
    assertTrue(added4);

    assertEquals(3, cache.size());

    assertNotNull(cache.get(key1)); // hot subject1 should still be present
    assertNull(cache.get(key2)); // subject2 should be evicted
    assertNotNull(cache.get(key4)); // newest subject4 should be present

    assertEquals("variant1", cache.get(key1).variant);
    assertEquals("allocation1", cache.get(key1).allocation);
  }

  private static ExposureEvent createEvent(
      String flag, String subject, String variant, String allocation) {
    return new ExposureEvent(
        System.currentTimeMillis(),
        new Allocation(allocation),
        new Flag(flag),
        new Variant(variant),
        new Subject(subject, emptyMap()));
  }
}
