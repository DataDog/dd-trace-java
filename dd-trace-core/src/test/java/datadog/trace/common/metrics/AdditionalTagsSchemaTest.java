package datadog.trace.common.metrics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import org.junit.jupiter.api.Test;

class AdditionalTagsSchemaTest {

  @Test
  void emptyConfigReturnsSharedEmptySchema() {
    assertSame(AdditionalTagsSchema.EMPTY, AdditionalTagsSchema.from(null));
    assertSame(AdditionalTagsSchema.EMPTY, AdditionalTagsSchema.from(Collections.emptySet()));
  }

  @Test
  void schemaSortsKeysAlphabetically() {
    AdditionalTagsSchema schema =
        AdditionalTagsSchema.from(new LinkedHashSet<>(Arrays.asList("region", "tenant_id", "az")));
    assertArrayEquals(new String[] {"az", "region", "tenant_id"}, schema.names);
  }

  @Test
  void schemaSortsAndCapsAtMaxTagKeys() {
    LinkedHashSet<String> configured = new LinkedHashSet<>();
    // 6 distinct keys, more than MAX_ADDITIONAL_TAG_KEYS (4). Sort alphabetically, drop the last 2.
    for (int i = 0; i < 6; i++) {
      configured.add(String.format("tag%02d", i));
    }
    AdditionalTagsSchema schema = AdditionalTagsSchema.from(configured);
    assertEquals(AdditionalTagsSchema.MAX_ADDITIONAL_TAG_KEYS, schema.size());
    assertArrayEquals(new String[] {"tag00", "tag01", "tag02", "tag03"}, schema.names);
  }

  @Test
  void rejectsEmptyAndColonContainingKeys() {
    AdditionalTagsSchema schema =
        AdditionalTagsSchema.from(
            new LinkedHashSet<>(Arrays.asList("region", "", "bad:key", "tenant_id")));
    // Empty key and "bad:key" are dropped; only the two valid keys remain.
    assertArrayEquals(new String[] {"region", "tenant_id"}, schema.names);
  }

  @Test
  void allInvalidKeysReturnsEmptySchema() {
    AdditionalTagsSchema schema =
        AdditionalTagsSchema.from(new LinkedHashSet<>(Arrays.asList("", "also:bad")));
    assertSame(AdditionalTagsSchema.EMPTY, schema);
  }

  @Test
  void emptySchemaHasZeroSize() {
    AdditionalTagsSchema schema = AdditionalTagsSchema.EMPTY;
    assertEquals(0, schema.size());
    assertTrue(schema.names.length == 0);
  }
}
