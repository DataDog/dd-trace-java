package datadog.trace.api.openfeature;

import static java.util.Arrays.asList;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded, provider-local history of observed assignments, independent of destination delivery. */
final class ExposureAdvisoryCache {
  private final Map<List<String>, List<Object>> assignments;

  ExposureAdvisoryCache(final int capacity) {
    assignments =
        new LinkedHashMap<List<String>, List<Object>>(16, 0.75f, true) {
          @Override
          protected boolean removeEldestEntry(final Map.Entry<List<String>, List<Object>> entry) {
            return size() > capacity;
          }
        };
  }

  /** Returns true when this subject's last observed assignment for this flag is unchanged. */
  synchronized boolean observe(
      final String flag,
      final String subject,
      final String allocation,
      final String variant,
      final Integer serialId) {
    final List<String> key = asList(flag, subject);
    final List<Object> assignment = asList(allocation, variant, serialId);
    return assignment.equals(assignments.put(key, assignment));
  }
}
