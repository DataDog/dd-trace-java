package datadog.trace.common.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.metrics.agent.AgentMeter;
import datadog.metrics.api.statsd.StatsDClient;
import datadog.metrics.impl.DDSketchHistograms;
import datadog.metrics.impl.MonitoringImpl;
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
    AggregateTable table = newTable(schema);

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
    AggregateTable table = newTable(schema);

    AggregateEntry first = table.findOrInsert(snapshot(schema, "us-east-1"));
    AggregateEntry second = table.findOrInsert(snapshot(schema, "us-east-1"));

    assertSame(first, second);
    assertEquals(1, table.size());
  }

  // ---------- helpers ----------

  private static AdditionalTagsSchema schemaFor(String... names) {
    return AdditionalTagsSchema.from(new LinkedHashSet<>(Arrays.asList(names)));
  }

  private static AggregateTable newTable(AdditionalTagsSchema schema) {
    return new AggregateTable(256, schema);
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
}
