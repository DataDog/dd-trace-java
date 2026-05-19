package datadog.trace.common.metrics;

import static datadog.trace.common.metrics.ConflatingMetricsAggregator.MAX_ADDITIONAL_TAG_KEYS;
import static datadog.trace.common.metrics.ConflatingMetricsAggregator.normalizeAdditionalTagKeys;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class ConflatingMetricsAggregatorNormalizationTest {

  @Test
  void nullOrEmptyProducesEmptyList() {
    assertEquals(Collections.emptyList(), normalizeAdditionalTagKeys(null));
    assertEquals(
        Collections.emptyList(), normalizeAdditionalTagKeys(Collections.<String>emptySet()));
  }

  @Test
  void resultIsSortedAlphabetically() {
    Set<String> configured = new LinkedHashSet<>(Arrays.asList("region", "tenant_id", "az"));
    assertEquals(
        Arrays.asList("az", "region", "tenant_id"), normalizeAdditionalTagKeys(configured));
  }

  @Test
  void resultIsImmutable() {
    Set<String> configured = new LinkedHashSet<>(Arrays.asList("region", "tenant_id"));
    List<String> normalized = normalizeAdditionalTagKeys(configured);
    assertThrows(UnsupportedOperationException.class, () -> normalized.add("oops"));
  }

  @Test
  void inputOrderDoesNotAffectResult() {
    Set<String> a = new LinkedHashSet<>(Arrays.asList("region", "tenant_id"));
    Set<String> b = new LinkedHashSet<>(Arrays.asList("tenant_id", "region"));
    assertEquals(normalizeAdditionalTagKeys(a), normalizeAdditionalTagKeys(b));
  }

  @Test
  void exceedingMaxKeysTruncatesAfterSort() {
    Set<String> configured = new TreeSet<>();
    for (int i = 0; i < MAX_ADDITIONAL_TAG_KEYS + 5; i++) {
      configured.add(String.format("tag_%02d", i));
    }
    List<String> normalized = normalizeAdditionalTagKeys(configured);
    assertEquals(MAX_ADDITIONAL_TAG_KEYS, normalized.size());
    assertTrue(normalized.contains("tag_00"));
    assertTrue(normalized.contains(String.format("tag_%02d", MAX_ADDITIONAL_TAG_KEYS - 1)));
  }
}
