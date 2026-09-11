package com.datadog.featureflag;

import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.featureflag.exposure.Allocation;
import datadog.trace.api.featureflag.exposure.ExposureEvent;
import datadog.trace.api.featureflag.exposure.Flag;
import datadog.trace.api.featureflag.exposure.Subject;
import datadog.trace.api.featureflag.exposure.Variant;
import org.junit.jupiter.api.Test;

class ExposureAdmissionCacheTest {

  @Test
  void rejectsNonPositiveCapacity() {
    assertThrows(IllegalArgumentException.class, () -> new ExposureAdmissionCache(0));
  }

  @Test
  void admitsOnlyExactRecentIdentity() {
    final ExposureAdmissionCache cache = new ExposureAdmissionCache(4);
    cache.add(event("flag", "subject", "variant", "allocation"));

    assertTrue(cache.contains("flag", "subject", "variant", "allocation"));
    assertFalse(cache.contains("other", "subject", "variant", "allocation"));
    assertFalse(cache.contains("flag", "other", "variant", "allocation"));
    assertFalse(cache.contains("flag", "subject", "other", "allocation"));
    assertFalse(cache.contains("flag", "subject", "variant", "other"));
  }

  @Test
  void changedValueReplacesPreviousValue() {
    final ExposureAdmissionCache cache = new ExposureAdmissionCache(4);
    cache.add(event("flag", "subject", "first", "allocation"));
    cache.add(event("flag", "subject", "second", "allocation"));

    assertFalse(cache.contains("flag", "subject", "first", "allocation"));
    assertTrue(cache.contains("flag", "subject", "second", "allocation"));
  }

  @Test
  void evictsOldestIdentityAtCapacity() {
    final ExposureAdmissionCache cache = new ExposureAdmissionCache(2);
    cache.add(event("first", "subject", "variant", "allocation"));
    cache.add(event("second", "subject", "variant", "allocation"));
    cache.add(event("third", "subject", "variant", "allocation"));

    assertEquals(2, cache.size());
    assertFalse(cache.contains("first", "subject", "variant", "allocation"));
    assertTrue(cache.contains("second", "subject", "variant", "allocation"));
    assertTrue(cache.contains("third", "subject", "variant", "allocation"));
  }

  @Test
  void clearRemovesRetainedCustomerValues() {
    final ExposureAdmissionCache cache = new ExposureAdmissionCache(2);
    cache.add(event("flag", "subject", "variant", "allocation"));

    cache.clear();

    assertEquals(0, cache.size());
    assertFalse(cache.contains("flag", "subject", "variant", "allocation"));
  }

  @Test
  void closeRejectsFutureValues() {
    final ExposureAdmissionCache cache = new ExposureAdmissionCache(2);
    final ExposureEvent event = event("flag", "subject", "variant", "allocation");
    cache.add(event);

    cache.close();
    cache.add(event);

    assertTrue(cache.isClosed());
    assertEquals(0, cache.size());
  }

  @Test
  void supportsNullIdentityFields() {
    final ExposureAdmissionCache cache = new ExposureAdmissionCache(2);
    final ExposureEvent event = new ExposureEvent(1, null, null, null, null);

    cache.add(event);

    assertTrue(cache.contains(null, null, null, null));
    assertEquals(cache.lockFor(event), cache.lockFor(event));
  }

  @Test
  void identityKeyUsesFlagAndSubject() {
    final ExposureAdmissionCache.Key key = new ExposureAdmissionCache.Key("flag", "subject");
    final ExposureAdmissionCache.Key equal = new ExposureAdmissionCache.Key("flag", "subject");

    assertEquals(key, key);
    assertEquals(key, equal);
    assertEquals(key.hashCode(), equal.hashCode());
    assertNotEquals(key, null);
    assertNotEquals(key, "not-a-key");
    assertNotEquals(key, new ExposureAdmissionCache.Key("other", "subject"));
    assertNotEquals(key, new ExposureAdmissionCache.Key("flag", "other"));
  }

  private static ExposureEvent event(
      final String flag, final String subject, final String variant, final String allocation) {
    return new ExposureEvent(
        1,
        new Allocation(allocation),
        new Flag(flag),
        new Variant(variant),
        new Subject(subject, emptyMap()));
  }
}
