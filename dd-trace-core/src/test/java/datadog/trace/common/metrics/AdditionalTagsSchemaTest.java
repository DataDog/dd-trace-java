package datadog.trace.common.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.trace.core.monitor.HealthMetrics;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import org.junit.jupiter.api.Test;

class AdditionalTagsSchemaTest {

  private static final int LIMIT = 4;

  @Test
  void emptyConfigReturnsSharedEmptySchema() {
    assertSame(
        AdditionalTagsSchema.EMPTY, AdditionalTagsSchema.from(null, LIMIT, HealthMetrics.NO_OP));
    assertSame(
        AdditionalTagsSchema.EMPTY,
        AdditionalTagsSchema.from(Collections.emptySet(), LIMIT, HealthMetrics.NO_OP));
  }

  @Test
  void schemaSortsKeysAlphabetically() {
    AdditionalTagsSchema schema =
        AdditionalTagsSchema.from(
            new LinkedHashSet<>(Arrays.asList("region", "tenant_id", "az")),
            LIMIT,
            HealthMetrics.NO_OP);
    assertEquals(3, schema.size());
    assertEquals("az", schema.name(0));
    assertEquals("region", schema.name(1));
    assertEquals("tenant_id", schema.name(2));
  }

  @Test
  void schemaDedupesAndCapsAtMaxTagKeys() {
    LinkedHashSet<String> configured = new LinkedHashSet<>();
    // 12 distinct keys, more than MAX_ADDITIONAL_TAG_KEYS (10). Sort by alphabetical, drop the
    // last 2.
    for (int i = 0; i < 12; i++) {
      configured.add(String.format("tag%02d", i));
    }
    AdditionalTagsSchema schema =
        AdditionalTagsSchema.from(configured, LIMIT, HealthMetrics.NO_OP);
    assertEquals(AdditionalTagsSchema.MAX_ADDITIONAL_TAG_KEYS, schema.size());
    for (int i = 0; i < AdditionalTagsSchema.MAX_ADDITIONAL_TAG_KEYS; i++) {
      assertEquals(String.format("tag%02d", i), schema.name(i));
    }
  }

  @Test
  void registerReturnsBlockedSentinelForOverLengthValue() {
    AdditionalTagsSchema schema =
        AdditionalTagsSchema.from(
            new LinkedHashSet<>(Arrays.asList("region")), LIMIT, HealthMetrics.NO_OP);
    StringBuilder over = new StringBuilder(AdditionalTagsSchema.MAX_ADDITIONAL_TAG_VALUE_LENGTH + 1);
    for (int i = 0; i <= AdditionalTagsSchema.MAX_ADDITIONAL_TAG_VALUE_LENGTH; i++) {
      over.append('x');
    }
    assertEquals("region:blocked_by_tracer", schema.register(0, over.toString()).toString());
  }

  @Test
  void emptySchemaHasZeroSize() {
    AdditionalTagsSchema schema = AdditionalTagsSchema.EMPTY;
    assertEquals(0, schema.size());
  }
}
