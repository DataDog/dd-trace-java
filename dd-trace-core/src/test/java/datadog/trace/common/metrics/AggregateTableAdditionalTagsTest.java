package datadog.trace.common.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.metrics.agent.AgentMeter;
import datadog.metrics.api.statsd.StatsDClient;
import datadog.metrics.impl.DDSketchHistograms;
import datadog.metrics.impl.MonitoringImpl;
import datadog.trace.core.monitor.HealthMetrics;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class AggregateTableAdditionalTagsTest {

  @BeforeAll
  static void initAgentMeter() {
    MonitoringImpl monitoring = new MonitoringImpl(StatsDClient.NO_OP, 1, TimeUnit.SECONDS);
    AgentMeter.registerIfAbsent(StatsDClient.NO_OP, monitoring, DDSketchHistograms.FACTORY);
    monitoring.newTimer("test.init");
  }

  @Test
  void distinctAdditionalTagValuesYieldDistinctEntries() {
    AdditionalTagsSchema schema = schemaFor("region");
    AggregateTable table = newTable(schema, 100);

    AggregateEntry usEast = table.findOrInsert(snapshot(schema, "us-east-1"));
    AggregateEntry euWest = table.findOrInsert(snapshot(schema, "eu-west-1"));

    assertNotNull(usEast);
    assertNotNull(euWest);
    assertNotSame(usEast, euWest);
    assertEquals(2, table.size());
  }

  @Test
  void sameAdditionalTagValuesShareEntry() {
    AdditionalTagsSchema schema = schemaFor("region");
    AggregateTable table = newTable(schema, 100);

    AggregateEntry first = table.findOrInsert(snapshot(schema, "us-east-1"));
    AggregateEntry second = table.findOrInsert(snapshot(schema, "us-east-1"));

    assertSame(first, second);
    assertEquals(1, table.size());
  }

  @Test
  void overlongValuesShareTheBlockedSentinelEntry() {
    AdditionalTagsSchema schema = schemaFor("region");
    AggregateTable table = newTable(schema, 100);

    String overlong = repeat('a', AdditionalTagsSchema.MAX_ADDITIONAL_TAG_VALUE_LENGTH + 1);
    String evenLonger = repeat('b', AdditionalTagsSchema.MAX_ADDITIONAL_TAG_VALUE_LENGTH + 50);

    AggregateEntry first = table.findOrInsert(snapshot(schema, overlong));
    AggregateEntry second = table.findOrInsert(snapshot(schema, evenLonger));

    // Both values get replaced with the same per-key blocked sentinel, so they collapse onto
    // one entry rather than fragmenting.
    assertSame(first, second);
    assertEquals(1, table.size());
    assertEquals("region:blocked_by_tracer", first.getAdditionalTags()[0].toString());
  }

  @Test
  void cardinalityCapCollapsesNewEntriesToBlockedSentinel() {
    AdditionalTagsSchema schema = schemaFor("region");
    AggregateTable table = newTable(schema, /*cardinalityLimit*/ 2);

    // Two distinct values admitted before the cap closes.
    AggregateEntry first = table.findOrInsert(snapshot(schema, "us-east-1"));
    AggregateEntry second = table.findOrInsert(snapshot(schema, "eu-west-1"));
    assertNotSame(first, second);

    // Third distinct value should collapse onto the blocked sentinel; fourth too.
    AggregateEntry third = table.findOrInsert(snapshot(schema, "ap-south-1"));
    AggregateEntry fourth = table.findOrInsert(snapshot(schema, "us-west-2"));

    assertSame(third, fourth);
    assertEquals("region:blocked_by_tracer", third.getAdditionalTags()[0].toString());
    // 2 in-budget entries + 1 collapsed blocked-sentinel entry = 3 total
    assertEquals(3, table.size());
  }

  @Test
  void cardinalityCapDoesNotBlockExistingEntries() {
    AdditionalTagsSchema schema = schemaFor("region");
    AggregateTable table = newTable(schema, /*cardinalityLimit*/ 1);

    AggregateEntry first = table.findOrInsert(snapshot(schema, "us-east-1"));
    // Now at cap. A repeat of the same value should still hit the existing entry.
    AggregateEntry firstAgain = table.findOrInsert(snapshot(schema, "us-east-1"));
    assertSame(first, firstAgain);

    // But a brand-new value should go to the blocked sentinel.
    AggregateEntry blocked = table.findOrInsert(snapshot(schema, "eu-west-1"));
    assertNotSame(first, blocked);
    assertEquals("region:blocked_by_tracer", blocked.getAdditionalTags()[0].toString());
  }

  // ---------- helpers ----------

  private static AdditionalTagsSchema schemaFor(String... names) {
    return AdditionalTagsSchema.from(new LinkedHashSet<>(Arrays.asList(names)));
  }

  private static AggregateTable newTable(AdditionalTagsSchema schema, int cardinalityLimit) {
    AdditionalTagsCardinalityLimiter limiter =
        new AdditionalTagsCardinalityLimiter(cardinalityLimit, HealthMetrics.NO_OP);
    return new AggregateTable(256, schema, limiter, HealthMetrics.NO_OP);
  }

  private static SpanSnapshot snapshot(AdditionalTagsSchema schema, String regionValue) {
    String[] values = new String[schema.size()];
    values[0] = regionValue;
    return new SpanSnapshot(
        "resource",
        "service",
        "operation",
        null,
        "web",
        (short) 200,
        false,
        true,
        "client",
        null,
        null,
        null,
        null,
        null,
        values,
        0L);
  }

  private static String repeat(char ch, int n) {
    char[] chars = new char[n];
    Arrays.fill(chars, ch);
    return new String(chars);
  }
}
