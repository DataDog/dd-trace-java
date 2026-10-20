package com.datadog.openfeature.internal.exposure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class ExposureDeduplicationCacheTest {

  @Test
  void recordsANewExposure() {
    final ExposureDeduplicationCache cache = new ExposureDeduplicationCache(5);

    assertFalse(cache.contains("flag", "subject", "allocation", "variant", null));
    assertTrue(cache.recordIfChanged("flag", "subject", "allocation", "variant", null));

    assertEquals(1, cache.size());
    assertTrue(cache.contains("flag", "subject", "allocation", "variant", null));
  }

  @Test
  void doesNotRecordARepeatedExposureAsChanged() {
    final ExposureDeduplicationCache cache = new ExposureDeduplicationCache(5);
    cache.recordIfChanged("flag", "subject", "allocation", "variant", null);

    assertFalse(cache.recordIfChanged("flag", "subject", "allocation", "variant", null));
    assertEquals(1, cache.size());
  }

  @Test
  void replacesTheExposureOfASubjectWhenItChanges() {
    final ExposureDeduplicationCache cache = new ExposureDeduplicationCache(5);
    cache.recordIfChanged("flag", "subject", "allocation1", "variant1", null);

    assertTrue(cache.recordIfChanged("flag", "subject", "allocation2", "variant2", null));

    assertEquals(1, cache.size());
    assertTrue(cache.contains("flag", "subject", "allocation2", "variant2", null));
    assertFalse(cache.contains("flag", "subject", "allocation1", "variant1", null));
  }

  @TableTest({
    "scenario            | first | second | changed",
    "serial id appears   |       | 7      | true   ",
    "serial id vanishes  | 7     |        | true   ",
    "serial id changes   | 7     | 8      | true   ",
    "serial id unchanged | 7     | 7      | false  ",
    "both absent         |       |        | false  ",
    "zero then absent    | 0     |        | true   ",
    "absent then zero    |       | 0      | true   "
  })
  void comparesTheSerialId(final Integer first, final Integer second, final boolean changed) {
    final ExposureDeduplicationCache cache = new ExposureDeduplicationCache(5);
    cache.recordIfChanged("flag", "subject", "allocation", "variant", first);

    assertEquals(
        changed, cache.recordIfChanged("flag", "subject", "allocation", "variant", second));
  }

  @Test
  void sendsAnExposurePerSerialIdTransition() {
    final ExposureDeduplicationCache cache = new ExposureDeduplicationCache(5);

    assertTrue(cache.recordIfChanged("flag", "subject", "allocation", "variant", 7));
    assertTrue(cache.recordIfChanged("flag", "subject", "allocation", "variant", 8));
    assertTrue(cache.recordIfChanged("flag", "subject", "allocation", "variant", 7));
  }

  @Test
  void keysBySubjectAndFlag() {
    final ExposureDeduplicationCache cache = new ExposureDeduplicationCache(10);
    for (int index = 0; index < 5; index++) {
      assertTrue(cache.recordIfChanged("flag", "subject" + index, "allocation", "variant", null));
      assertTrue(cache.recordIfChanged("flag" + index, "subject", "allocation", "variant", null));
    }

    assertEquals(10, cache.size());
  }

  @Test
  void acceptsNullKeys() {
    final ExposureDeduplicationCache cache = new ExposureDeduplicationCache(5);
    cache.recordIfChanged(null, null, "allocation", "variant", null);

    assertFalse(cache.recordIfChanged(null, null, "allocation", "variant", null));
    assertTrue(cache.contains(null, null, "allocation", "variant", null));
    assertEquals(1, cache.size());
  }

  @Test
  void evictsTheLeastRecentlyUsedSubject() {
    final ExposureDeduplicationCache cache = new ExposureDeduplicationCache(2);
    cache.recordIfChanged("flag", "user-1", "allocation", "on", 7);
    cache.recordIfChanged("flag", "user-2", "allocation", "on", 7);
    assertTrue(cache.contains("flag", "user-1", "allocation", "on", 7));
    cache.recordIfChanged("flag", "user-3", "allocation", "on", 7);

    assertEquals(2, cache.size());
    assertTrue(cache.contains("flag", "user-1", "allocation", "on", 7));
    assertFalse(cache.contains("flag", "user-2", "allocation", "on", 7));
    assertTrue(cache.contains("flag", "user-3", "allocation", "on", 7));
    assertFalse(cache.contains("flag", "user-1", "allocation", "on", 8));
  }

  @Test
  void updatingASubjectKeepsItHot() {
    final ExposureDeduplicationCache cache = new ExposureDeduplicationCache(3);
    cache.recordIfChanged("flag1", "subject1", "allocation1", "variant1", null);
    cache.recordIfChanged("flag2", "subject2", "allocation2", "variant2", null);
    cache.recordIfChanged("flag3", "subject3", "allocation3", "variant3", null);
    cache.recordIfChanged("flag1", "subject1", "allocation2", "variant2", null);
    cache.recordIfChanged("flag4", "subject4", "allocation4", "variant4", null);

    assertEquals(3, cache.size());
    assertTrue(cache.contains("flag1", "subject1", "allocation2", "variant2", null));
    assertFalse(cache.contains("flag2", "subject2", "allocation2", "variant2", null));
    assertTrue(cache.contains("flag4", "subject4", "allocation4", "variant4", null));
  }

  @Test
  void boundsItsSize() {
    final ExposureDeduplicationCache single = new ExposureDeduplicationCache(1);
    single.recordIfChanged("flag1", "subject1", "allocation", "variant", null);
    single.recordIfChanged("flag2", "subject2", "allocation", "variant", null);
    final ExposureDeduplicationCache none = new ExposureDeduplicationCache(0);

    assertTrue(none.recordIfChanged("flag", "subject", "allocation", "variant", null));
    assertEquals(1, single.size());
    assertEquals(0, none.size());
    assertEquals(1 << 16, ExposureDeduplicationCache.CAPACITY);
    assertEquals(0, new ExposureDeduplicationCache().size());
  }

  @Test
  void clearForgetsEveryExposure() {
    final ExposureDeduplicationCache cache = new ExposureDeduplicationCache(5);
    cache.recordIfChanged("flag", "subject", "allocation", "variant", null);

    cache.clear();

    assertEquals(0, cache.size());
    assertFalse(cache.contains("flag", "subject", "allocation", "variant", null));
  }
}
