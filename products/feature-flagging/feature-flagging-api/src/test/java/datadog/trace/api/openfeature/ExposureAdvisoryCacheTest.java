package datadog.trace.api.openfeature;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ExposureAdvisoryCacheTest {
  @Test
  void flagsSubjectsAndEveryAssignmentDimensionParticipate() {
    final ExposureAdvisoryCache cache = new ExposureAdvisoryCache(10);
    assertFalse(cache.observe("flag", "user", "allocation", "on", 1));
    assertTrue(cache.observe("flag", "user", "allocation", "on", 1));
    assertFalse(cache.observe("other", "user", "allocation", "on", 1));
    assertFalse(cache.observe("flag", "other", "allocation", "on", 1));
    assertFalse(cache.observe("flag", "user", "changed", "on", 1));
    assertFalse(cache.observe("flag", "user", "changed", "off", 1));
    assertFalse(cache.observe("flag", "user", "changed", "off", 2));
    assertFalse(cache.observe("flag", "user", "changed", "off", null));
    assertTrue(cache.observe("flag", "user", "changed", "off", null));
    // Returning to an earlier assignment is also a change.
    assertFalse(cache.observe("flag", "user", "allocation", "on", 1));
  }

  @Test
  void leastRecentlyObservedAssignmentsAreEvicted() {
    final ExposureAdvisoryCache cache = new ExposureAdvisoryCache(2);
    assertFalse(cache.observe("flag", "a", "allocation", "on", 1));
    assertFalse(cache.observe("flag", "b", "allocation", "on", 1));
    assertTrue(cache.observe("flag", "a", "allocation", "on", 1));
    assertFalse(cache.observe("flag", "c", "allocation", "on", 1));
    assertTrue(cache.observe("flag", "a", "allocation", "on", 1));
    assertFalse(cache.observe("flag", "b", "allocation", "on", 1));
  }
}
