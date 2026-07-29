package datadog.openfeature.internal.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ExposureDeduplicationCacheTest {

  @Test
  void emitsNewExposure() {
    final ExposureDeduplicationCache cache = new ExposureDeduplicationCache(5);

    assertTrue(cache.shouldEmit("flag", "subject", "variant", "allocation"));
    assertEquals(1, cache.size());
  }

  @Test
  void suppressesDuplicateExposure() {
    final ExposureDeduplicationCache cache = new ExposureDeduplicationCache(5);

    cache.shouldEmit("flag", "subject", "variant", "allocation");

    assertFalse(cache.shouldEmit("flag", "subject", "variant", "allocation"));
    assertEquals(1, cache.size());
  }

  @Test
  void emitsChangedVariantOrAllocation() {
    final ExposureDeduplicationCache cache = new ExposureDeduplicationCache(5);
    final ExposureDeduplicationCache.Key key =
        new ExposureDeduplicationCache.Key("flag", "subject");

    assertTrue(cache.shouldEmit("flag", "subject", "variant1", "allocation1"));
    assertTrue(cache.shouldEmit("flag", "subject", "variant2", "allocation2"));
    assertEquals(1, cache.size());
    assertEquals("variant2", cache.getValue(key).variant);
    assertEquals("allocation2", cache.getValue(key).allocation);
  }

  @Test
  void evictsLeastRecentlyUsedExposure() {
    final ExposureDeduplicationCache cache = new ExposureDeduplicationCache(2);
    final ExposureDeduplicationCache.Key first =
        new ExposureDeduplicationCache.Key("flag1", "subject1");
    final ExposureDeduplicationCache.Key third =
        new ExposureDeduplicationCache.Key("flag3", "subject3");

    cache.shouldEmit("flag1", "subject1", "variant1", "allocation1");
    cache.shouldEmit("flag2", "subject2", "variant2", "allocation2");
    cache.shouldEmit("flag3", "subject3", "variant3", "allocation3");

    assertEquals(2, cache.size());
    assertNull(cache.getValue(first));
    assertNotNull(cache.getValue(third));
  }

  @Test
  void supportsZeroCapacity() {
    final ExposureDeduplicationCache cache = new ExposureDeduplicationCache(0);

    assertTrue(cache.shouldEmit("flag", "subject", "variant", "allocation"));
    assertEquals(0, cache.size());
  }

  @Test
  void distinguishesFlagsAndSubjects() {
    final ExposureDeduplicationCache cache = new ExposureDeduplicationCache(10);

    for (int index = 0; index < 5; index++) {
      assertTrue(cache.shouldEmit("flag", "subject" + index, "variant", "allocation"));
      assertTrue(cache.shouldEmit("flag" + index, "subject", "variant", "allocation"));
    }

    assertEquals(10, cache.size());
  }

  @Test
  void supportsNullKeys() {
    final ExposureDeduplicationCache cache = new ExposureDeduplicationCache(5);

    assertTrue(cache.shouldEmit(null, null, "variant", "allocation"));
    assertFalse(cache.shouldEmit(null, null, "variant", "allocation"));
    assertEquals(1, cache.size());
  }

  @Test
  void updateMovesExposureToMostRecentPosition() {
    final ExposureDeduplicationCache cache = new ExposureDeduplicationCache(3);
    final ExposureDeduplicationCache.Key first =
        new ExposureDeduplicationCache.Key("flag1", "subject1");
    final ExposureDeduplicationCache.Key second =
        new ExposureDeduplicationCache.Key("flag2", "subject2");

    cache.shouldEmit("flag1", "subject1", "variant1", "allocation1");
    cache.shouldEmit("flag2", "subject2", "variant2", "allocation2");
    cache.shouldEmit("flag3", "subject3", "variant3", "allocation3");
    cache.shouldEmit("flag1", "subject1", "variant4", "allocation4");
    cache.shouldEmit("flag4", "subject4", "variant4", "allocation4");

    assertNotNull(cache.getValue(first));
    assertNull(cache.getValue(second));
  }

  @Test
  void duplicateMovesExposureToMostRecentPosition() {
    final ExposureDeduplicationCache cache = new ExposureDeduplicationCache(3);
    final ExposureDeduplicationCache.Key first =
        new ExposureDeduplicationCache.Key("flag1", "subject1");
    final ExposureDeduplicationCache.Key second =
        new ExposureDeduplicationCache.Key("flag2", "subject2");

    cache.shouldEmit("flag1", "subject1", "variant1", "allocation1");
    cache.shouldEmit("flag2", "subject2", "variant2", "allocation2");
    cache.shouldEmit("flag3", "subject3", "variant3", "allocation3");
    assertFalse(cache.shouldEmit("flag1", "subject1", "variant1", "allocation1"));
    cache.shouldEmit("flag4", "subject4", "variant4", "allocation4");

    assertNotNull(cache.getValue(first));
    assertNull(cache.getValue(second));
  }
}
