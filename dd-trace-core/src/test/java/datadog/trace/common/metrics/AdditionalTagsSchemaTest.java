package datadog.trace.common.metrics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import datadog.trace.api.metrics.StatsMetrics;
import datadog.trace.core.monitor.HealthMetrics;
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

  @Test
  void perKeyCardinalityBudgetsAreIndependent() {
    // Two configured keys, cardinality limit 1 each. Exhausting one key's budget must neither
    // consume nor block the other's -- each key gets its own TagCardinalityHandler.
    AdditionalTagsSchema schema =
        AdditionalTagsSchema.from(
            new LinkedHashSet<>(Arrays.asList("region", "tenant_id")), 1, true);
    int region = indexOf(schema, "region");
    int tenant = indexOf(schema, "tenant_id");

    // region: first value fits, second collapses to the blocked sentinel.
    assertEquals("region:us-east-1", schema.register(region, "us-east-1").toString());
    assertEquals("region:tracer_blocked_value", schema.register(region, "eu-west-1").toString());

    // tenant_id is untouched by region's exhaustion: its first value still flows through, and its
    // own budget collapses only its own second value.
    assertEquals("tenant_id:acme-corp", schema.register(tenant, "acme-corp").toString());
    assertEquals("tenant_id:tracer_blocked_value", schema.register(tenant, "globex").toString());
  }

  @Test
  void resetHandlersReportsCardinalityCollapseUnderFieldName() {
    // Cardinality-collapsed values surface as the health-metric tag
    // "collapsed:additional_metric_tags"
    // -- the lowercased protobuf field name, not the individual tag key -- per the approved
    // Cardinality Limits RFC.
    AdditionalTagsSchema schema =
        AdditionalTagsSchema.from(Collections.singleton("region"), 1, true);
    int region = indexOf(schema, "region");
    schema.register(region, "us-east-1"); // within budget
    schema.register(region, "eu-west-1"); // collapsed (cardinality)
    schema.register(region, "ap-south-1"); // collapsed (cardinality)

    HealthMetrics metrics = mock(HealthMetrics.class);
    schema.resetHandlers(metrics, new CardinalityLimitReporter());

    verify(metrics).onTagCardinalityBlocked(new String[] {"collapsed:additional_metric_tags"}, 2L);
    verifyNoMoreInteractions(metrics);
  }

  @Test
  void resetHandlersFeedsCollapseCountToTelemetry() {
    // The reset site also feeds the telemetry counter (independent of the statsd HealthMetrics
    // sink) under the same "collapsed:additional_metric_tags" reason tag.
    AdditionalTagsSchema schema =
        AdditionalTagsSchema.from(Collections.singleton("region"), 1, true);
    int region = indexOf(schema, "region");
    schema.register(region, "us-east-1"); // within budget
    schema.register(region, "eu-west-1"); // collapsed (cardinality)
    schema.register(region, "ap-south-1"); // collapsed (cardinality)

    // Drain any pre-existing delta so the assertion measures only this reset's contribution.
    drainTelemetryDelta("collapsed:additional_metric_tags");
    schema.resetHandlers(mock(HealthMetrics.class), new CardinalityLimitReporter());

    assertEquals(2L, drainTelemetryDelta("collapsed:additional_metric_tags"));
  }

  /** Reads and resets the telemetry delta for {@code reason}; 0 if no counter exists yet. */
  private static long drainTelemetryDelta(String reason) {
    for (StatsMetrics.TaggedCounter counter : StatsMetrics.getInstance().getTaggedCounters()) {
      if (reason.equals(counter.getTag())) {
        return counter.getValueAndReset();
      }
    }
    return 0L;
  }

  @Test
  void resetHandlersAggregatesCardinalityCollapseAcrossKeysUnderOneFieldTag() {
    // Two keys each collapse: the field-level health metric sums both under a single
    // "collapsed:additional_metric_tags" tag rather than emitting per key name.
    AdditionalTagsSchema schema =
        AdditionalTagsSchema.from(
            new LinkedHashSet<>(Arrays.asList("region", "tenant_id")), 1, true);
    int region = indexOf(schema, "region");
    int tenant = indexOf(schema, "tenant_id");
    schema.register(region, "us-east-1"); // within budget
    schema.register(region, "eu-west-1"); // collapsed
    schema.register(tenant, "acme-corp"); // within budget
    schema.register(tenant, "globex"); // collapsed

    HealthMetrics metrics = mock(HealthMetrics.class);
    schema.resetHandlers(metrics, new CardinalityLimitReporter());

    verify(metrics).onTagCardinalityBlocked(new String[] {"collapsed:additional_metric_tags"}, 2L);
    verifyNoMoreInteractions(metrics);
  }

  @Test
  void resetHandlersReportsLengthAndCardinalityCollapsesUnderDistinctTags() {
    // A single cycle with both a length collapse (oversized) and a cardinality collapse must emit
    // two distinct health-metric tags: oversized:additional_metric_tags and
    // collapsed:additional_metric_tags.
    AdditionalTagsSchema schema =
        AdditionalTagsSchema.from(Collections.singleton("region"), 1, true);
    int region = indexOf(schema, "region");
    schema.register(region, "us-east-1"); // within budget
    schema.register(region, "eu-west-1"); // collapsed (cardinality)
    schema.register(region, stringOfLength(201)); // oversized (length)

    HealthMetrics metrics = mock(HealthMetrics.class);
    schema.resetHandlers(metrics, new CardinalityLimitReporter());

    verify(metrics).onTagCardinalityBlocked(new String[] {"collapsed:additional_metric_tags"}, 1L);
    verify(metrics).onTagCardinalityBlocked(new String[] {"oversized:additional_metric_tags"}, 1L);
    verifyNoMoreInteractions(metrics);
  }

  @Test
  void resetHandlersWithNoBlocksDoesNotCallHealthMetrics() {
    AdditionalTagsSchema schema =
        AdditionalTagsSchema.from(Collections.singleton("region"), 4, true);
    schema.register(indexOf(schema, "region"), "us-east-1");

    HealthMetrics metrics = mock(HealthMetrics.class);
    schema.resetHandlers(metrics, new CardinalityLimitReporter());

    verifyNoMoreInteractions(metrics);
  }

  @Test
  void additionalTagValueAtLengthCapPassesAndOverCapCollapses() {
    // End-to-end through the shipped wiring: AdditionalTagsSchema builds its handlers with
    // MetricCardinalityLimits.ADDITIONAL_TAG_MAX_VALUE_LENGTH as the per-value length cap. A value
    // of exactly the cap passes verbatim; one character longer collapses to the blocked sentinel.
    // The cardinality budget is generous so only the length branch can block.
    int cap = MetricCardinalityLimits.ADDITIONAL_TAG_MAX_VALUE_LENGTH;
    // Pin the shipped magnitude: RFC and dd-trace-dotnet both use 200.
    assertEquals(200, cap);

    AdditionalTagsSchema schema =
        AdditionalTagsSchema.from(Collections.singleton("region"), 100, true);
    int region = indexOf(schema, "region");
    String atCap = stringOfLength(cap);
    String overCap = stringOfLength(cap + 1);

    assertEquals("region:" + atCap, schema.register(region, atCap).toString());
    assertEquals("region:tracer_blocked_value", schema.register(region, overCap).toString());

    // The over-cap value is a length collapse: it surfaces as oversized:additional_metric_tags,
    // not the cardinality collapsed: tag.
    HealthMetrics metrics = mock(HealthMetrics.class);
    schema.resetHandlers(metrics, new CardinalityLimitReporter());
    verify(metrics).onTagCardinalityBlocked(new String[] {"oversized:additional_metric_tags"}, 1L);
    verifyNoMoreInteractions(metrics);
  }

  private static String stringOfLength(int length) {
    char[] chars = new char[length];
    Arrays.fill(chars, 'x');
    return new String(chars);
  }

  private static int indexOf(AdditionalTagsSchema schema, String name) {
    for (int i = 0; i < schema.size(); i++) {
      if (name.equals(schema.name(i))) {
        return i;
      }
    }
    throw new IllegalArgumentException("no such configured key: " + name);
  }
}
