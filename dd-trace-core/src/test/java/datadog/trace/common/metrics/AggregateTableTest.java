package datadog.trace.common.metrics;

import static datadog.trace.common.metrics.AggregateMetric.ERROR_TAG;
import static datadog.trace.common.metrics.AggregateMetric.TOP_LEVEL_TAG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.metrics.agent.AgentMeter;
import datadog.metrics.api.statsd.StatsDClient;
import datadog.metrics.impl.DDSketchHistograms;
import datadog.metrics.impl.MonitoringImpl;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class AggregateTableTest {

  @BeforeAll
  static void initAgentMeter() {
    // AggregateMetric.recordOneDuration -> Histogram.accept needs AgentMeter to be initialized.
    // Mirror what AggregateMetricTest does.
    MonitoringImpl monitoring = new MonitoringImpl(StatsDClient.NO_OP, 1, TimeUnit.SECONDS);
    AgentMeter.registerIfAbsent(StatsDClient.NO_OP, monitoring, DDSketchHistograms.FACTORY);
    monitoring.newTimer("test.init");
  }

  @Test
  void insertOnMissReturnsNewAggregate() {
    AggregateTable table = new AggregateTable(8);
    SpanSnapshot s = snapshot("svc", "op", "client");

    AggregateMetric agg = table.findOrInsert(s);

    assertNotNull(agg);
    assertEquals(1, table.size());
    assertEquals(0, agg.getHitCount());
  }

  @Test
  void hitReturnsSameAggregateInstance() {
    AggregateTable table = new AggregateTable(8);
    SpanSnapshot s1 = snapshot("svc", "op", "client");
    SpanSnapshot s2 = snapshot("svc", "op", "client");

    AggregateMetric first = table.findOrInsert(s1);
    AggregateMetric second = table.findOrInsert(s2);

    assertSame(first, second);
    assertEquals(1, table.size());
  }

  @Test
  void differentKindFieldsAreDistinct() {
    AggregateTable table = new AggregateTable(8);

    AggregateMetric clientAgg = table.findOrInsert(snapshot("svc", "op", "client"));
    AggregateMetric serverAgg = table.findOrInsert(snapshot("svc", "op", "server"));

    assertNotSame(clientAgg, serverAgg);
    assertEquals(2, table.size());
  }

  @Test
  void peerTagPairsParticipateInIdentity() {
    AggregateTable table = new AggregateTable(8);
    SpanSnapshot withTags =
        builder("svc", "op", "client").peerTags("peer.hostname", "host-a").build();
    SpanSnapshot otherTags =
        builder("svc", "op", "client").peerTags("peer.hostname", "host-b").build();
    SpanSnapshot noTags = builder("svc", "op", "client").build();

    AggregateMetric a = table.findOrInsert(withTags);
    AggregateMetric b = table.findOrInsert(otherTags);
    AggregateMetric c = table.findOrInsert(noTags);

    assertNotSame(a, b);
    assertNotSame(a, c);
    assertNotSame(b, c);
    assertEquals(3, table.size());
  }

  @Test
  void cardinalityBlockedValuesCollapseIntoOneEntry() {
    // SERVICE_HANDLER has a cardinality limit of 32. With 50 distinct service names, services 33+
    // canonicalize to the "blocked_by_tracer" sentinel. Because the table hashes from the canonical
    // (post-handler) form, all blocked services land in the same bucket and merge into a single
    // entry rather than fragmenting.
    AggregateEntry.resetCardinalityHandlers();
    AggregateTable table = new AggregateTable(128);

    for (int i = 0; i < 50; i++) {
      AggregateMetric agg = table.findOrInsert(snapshot("svc-" + i, "op", "client"));
      assertNotNull(agg);
      agg.recordOneDuration(1L);
    }

    // 32 in-budget services + 1 collapsed "blocked_by_tracer" entry = 33 total.
    assertEquals(33, table.size());

    AggregateEntry.resetCardinalityHandlers();
  }

  @Test
  void capOverrunEvictsStaleEntry() {
    AggregateTable table = new AggregateTable(2);

    AggregateMetric stale = table.findOrInsert(snapshot("svc-a", "op", "client"));
    // do not record on stale -> hitCount stays at 0

    AggregateMetric live = table.findOrInsert(snapshot("svc-b", "op", "client"));
    live.recordOneDuration(10L | TOP_LEVEL_TAG); // hitCount=1, not evictable

    // table is full (size=2). Inserting a third should evict the stale one and succeed.
    AggregateMetric newcomer = table.findOrInsert(snapshot("svc-c", "op", "client"));
    assertNotNull(newcomer);
    assertEquals(2, table.size());

    // re-inserting the stale snapshot should miss now (it was evicted) and produce a fresh entry
    AggregateMetric staleAgain = table.findOrInsert(snapshot("svc-a", "op", "client"));
    assertNotSame(stale, staleAgain);
  }

  @Test
  void capOverrunWithNoStaleReturnsNull() {
    AggregateTable table = new AggregateTable(2);

    AggregateMetric a = table.findOrInsert(snapshot("svc-a", "op", "client"));
    AggregateMetric b = table.findOrInsert(snapshot("svc-b", "op", "client"));
    a.recordOneDuration(10L);
    b.recordOneDuration(20L);

    AggregateMetric c = table.findOrInsert(snapshot("svc-c", "op", "client"));
    assertNull(c);
    assertEquals(2, table.size());
  }

  @Test
  void expungeStaleAggregatesRemovesZeroHitsOnly() {
    AggregateTable table = new AggregateTable(16);

    AggregateMetric live = table.findOrInsert(snapshot("svc-live", "op", "client"));
    live.recordOneDuration(10L);
    AggregateMetric stale1 = table.findOrInsert(snapshot("svc-stale1", "op", "client"));
    AggregateMetric stale2 = table.findOrInsert(snapshot("svc-stale2", "op", "client"));
    assertEquals(3, table.size());
    assertEquals(0, stale1.getHitCount());
    assertEquals(0, stale2.getHitCount());

    table.expungeStaleAggregates();

    assertEquals(1, table.size());
    // the live entry must still be reachable
    assertSame(live, table.findOrInsert(snapshot("svc-live", "op", "client")));
  }

  @Test
  void forEachVisitsEveryEntry() {
    AggregateTable table = new AggregateTable(8);
    table.findOrInsert(snapshot("a", "op", "client")).recordOneDuration(1L);
    table.findOrInsert(snapshot("b", "op", "client")).recordOneDuration(2L);
    table.findOrInsert(snapshot("c", "op", "client")).recordOneDuration(3L | ERROR_TAG);

    Map<String, Long> visited = new HashMap<>();
    table.forEach(e -> visited.put(e.getService().toString(), e.aggregate.getDuration()));

    assertEquals(3, visited.size());
    assertEquals(1L, visited.get("a"));
    assertEquals(2L, visited.get("b"));
    assertEquals(3L, visited.get("c"));
  }

  @Test
  void clearEmptiesTheTable() {
    AggregateTable table = new AggregateTable(8);
    table.findOrInsert(snapshot("a", "op", "client"));
    table.findOrInsert(snapshot("b", "op", "client"));
    assertEquals(2, table.size());

    table.clear();

    assertTrue(table.isEmpty());
    assertEquals(0, table.size());
    // and re-insertion works after clear
    assertNotNull(table.findOrInsert(snapshot("a", "op", "client")));
  }

  @Test
  void encodedLabelsAreBuiltOnInsert() {
    AggregateTable table = new AggregateTable(4);
    List<AggregateEntry> seen = new ArrayList<>();
    table.findOrInsert(snapshot("svc", "op", "client"));
    table.forEach(seen::add);

    assertEquals(1, seen.size());
    AggregateEntry e = seen.get(0);
    assertEquals("svc", e.getService().toString());
    assertEquals("op", e.getOperationName().toString());
    assertEquals("client", e.getSpanKind().toString());
  }

  // ---------- helpers ----------

  private static SpanSnapshot snapshot(String service, String operation, String spanKind) {
    return builder(service, operation, spanKind).build();
  }

  private static SnapshotBuilder builder(String service, String operation, String spanKind) {
    return new SnapshotBuilder(service, operation, spanKind);
  }

  private static final class SnapshotBuilder {
    private final String service;
    private final String operation;
    private final String spanKind;
    private PeerTagSchema peerTagSchema;
    private String[] peerTagValues;
    private long tagAndDuration = 0L;

    SnapshotBuilder(String service, String operation, String spanKind) {
      this.service = service;
      this.operation = operation;
      this.spanKind = spanKind;
    }

    SnapshotBuilder peerTags(String... namesAndValues) {
      // Build a schema directly from the (name, value, name, value, ...) input. In production the
      // cached schema is owned by ClientStatsAggregator; these tests exercise AggregateTable and
      // can use a fresh per-snapshot schema -- canonicalization is content-based so cardinality
      // collapse still works across snapshots even with different handler instances.
      java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
      for (int i = 0; i < namesAndValues.length; i += 2) {
        names.add(namesAndValues[i]);
      }
      this.peerTagSchema = PeerTagSchema.of(names);
      this.peerTagValues = new String[peerTagSchema.size()];
      for (int i = 0; i < namesAndValues.length; i += 2) {
        for (int j = 0; j < peerTagSchema.size(); j++) {
          if (peerTagSchema.name(j).equals(namesAndValues[i])) {
            peerTagValues[j] = namesAndValues[i + 1];
            break;
          }
        }
      }
      return this;
    }

    SpanSnapshot build() {
      return new SpanSnapshot(
          "resource",
          service,
          operation,
          null,
          "web",
          (short) 200,
          false,
          true,
          spanKind,
          peerTagSchema,
          peerTagValues,
          null,
          null,
          null,
          tagAndDuration);
    }
  }
}
