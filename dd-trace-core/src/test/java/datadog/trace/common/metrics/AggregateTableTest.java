package datadog.trace.common.metrics;

import static datadog.trace.common.metrics.AggregateEntry.ERROR_TAG;
import static datadog.trace.common.metrics.AggregateEntry.TOP_LEVEL_TAG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import datadog.metrics.agent.AgentMeter;
import datadog.metrics.api.statsd.StatsDClient;
import datadog.metrics.impl.DDSketchHistograms;
import datadog.metrics.impl.MonitoringImpl;
import datadog.trace.core.monitor.HealthMetrics;
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
    // AggregateEntry.recordOneDuration -> Histogram.accept needs AgentMeter to be initialized.
    MonitoringImpl monitoring = new MonitoringImpl(StatsDClient.NO_OP, 1, TimeUnit.SECONDS);
    AgentMeter.registerIfAbsent(StatsDClient.NO_OP, monitoring, DDSketchHistograms.FACTORY);
    monitoring.newTimer("test.init");
  }

  @Test
  void insertOnMissReturnsNewAggregate() {
    AggregateTable table = new AggregateTable(8);
    SpanSnapshot s = snapshot("svc", "op", "client");

    AggregateEntry agg = table.findOrInsert(s);

    assertNotNull(agg);
    assertEquals(1, table.size());
    assertEquals(0, agg.getHitCount());
  }

  @Test
  void hitReturnsSameAggregateInstance() {
    AggregateTable table = new AggregateTable(8);
    SpanSnapshot s1 = snapshot("svc", "op", "client");
    SpanSnapshot s2 = snapshot("svc", "op", "client");

    AggregateEntry first = table.findOrInsert(s1);
    AggregateEntry second = table.findOrInsert(s2);

    assertSame(first, second);
    assertEquals(1, table.size());
  }

  @Test
  void differentKindFieldsAreDistinct() {
    AggregateTable table = new AggregateTable(8);

    AggregateEntry clientAgg = table.findOrInsert(snapshot("svc", "op", "client"));
    AggregateEntry serverAgg = table.findOrInsert(snapshot("svc", "op", "server"));

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

    AggregateEntry a = table.findOrInsert(withTags);
    AggregateEntry b = table.findOrInsert(otherTags);
    AggregateEntry c = table.findOrInsert(noTags);

    assertNotSame(a, b);
    assertNotSame(a, c);
    assertNotSame(b, c);
    assertEquals(3, table.size());
  }

  @Test
  void capOverrunEvictsStaleEntry() {
    AggregateTable table = new AggregateTable(2);

    AggregateEntry stale = table.findOrInsert(snapshot("svc-a", "op", "client"));
    // do not record on stale -> hitCount stays at 0

    AggregateEntry live = table.findOrInsert(snapshot("svc-b", "op", "client"));
    live.recordOneDuration(10L | TOP_LEVEL_TAG); // hitCount=1, not evictable

    // table is full (size=2). Inserting a third should evict the stale one and succeed.
    AggregateEntry newcomer = table.findOrInsert(snapshot("svc-c", "op", "client"));
    assertNotNull(newcomer);
    assertEquals(2, table.size());

    // re-inserting the stale snapshot should miss now (it was evicted) and produce a fresh entry
    AggregateEntry staleAgain = table.findOrInsert(snapshot("svc-a", "op", "client"));
    assertNotSame(stale, staleAgain);
  }

  @Test
  void backToBackEvictionsAllSucceed() {
    // Cursor amortization regression: cap the table, fill with stale entries, then force a
    // sequence of cap-overrun inserts. Each insert must succeed (evicting one stale entry and
    // inserting one new). The cursor field is internal, but if it were ever wedged (e.g.
    // pointing past the end of buckets, or not advancing after a successful eviction), some
    // later insert would fail to find a stale entry. Drives ~3x the capacity worth of inserts to
    // give wrap-around plenty of chances to misbehave.
    AggregateTable table = new AggregateTable(8);
    for (int i = 0; i < 8; i++) {
      table.findOrInsert(snapshot("init-" + i, "op", "client"));
    }
    for (int i = 0; i < 32; i++) {
      AggregateEntry inserted = table.findOrInsert(snapshot("post-" + i, "op", "client"));
      assertNotNull(
          inserted, "insert #" + i + " should evict a stale entry and succeed (table full)");
    }
    assertEquals(8, table.size());
  }

  @Test
  void clearResetsCursorForSubsequentEvictions() {
    // The cursor must reset to 0 on clear so a re-filled table doesn't start eviction at a
    // stale bucket index. Verified indirectly: clear and re-fill, then force an eviction; the
    // newcomer must successfully take a slot (which only works if a stale entry was found).
    AggregateTable table = new AggregateTable(4);

    // Fill, age, evict once -- cursor lands at some non-zero bucket
    for (int i = 0; i < 4; i++) {
      table.findOrInsert(snapshot("warm-" + i, "op", "client"));
    }
    table.findOrInsert(snapshot("evict-trigger", "op", "client"));

    table.clear();
    assertEquals(0, table.size());

    // Re-fill, age, force eviction -- should still find a stale entry from bucket 0 onward
    for (int i = 0; i < 4; i++) {
      table.findOrInsert(snapshot("fresh-" + i, "op", "client"));
    }
    AggregateEntry newcomer = table.findOrInsert(snapshot("post-clear", "op", "client"));
    assertNotNull(newcomer, "post-clear cap-overrun insert must succeed via cursor-reset evict");
  }

  @Test
  void capOverrunWithNoStaleReturnsNull() {
    AggregateTable table = new AggregateTable(2);

    AggregateEntry a = table.findOrInsert(snapshot("svc-a", "op", "client"));
    AggregateEntry b = table.findOrInsert(snapshot("svc-b", "op", "client"));
    a.recordOneDuration(10L);
    b.recordOneDuration(20L);

    AggregateEntry c = table.findOrInsert(snapshot("svc-c", "op", "client"));
    assertNull(c);
    assertEquals(2, table.size());
  }

  @Test
  void expungeStaleAggregatesRemovesZeroHitsOnly() {
    AggregateTable table = new AggregateTable(16);

    AggregateEntry live = table.findOrInsert(snapshot("svc-live", "op", "client"));
    live.recordOneDuration(10L);
    AggregateEntry stale1 = table.findOrInsert(snapshot("svc-stale1", "op", "client"));
    AggregateEntry stale2 = table.findOrInsert(snapshot("svc-stale2", "op", "client"));
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
    table.forEach(e -> visited.put(e.getService().toString(), e.getDuration()));

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

  @Test
  void nullAndEmptyOptionalFieldsCollapseToOneEntry() {
    // null and length-zero are treated as equivalent for optional fields, so snapshots that
    // differ only in null-vs-"" land on the same entry.
    AggregateTable table = new AggregateTable(8);

    SpanSnapshot snapNull = nullableSnapshot(null, null, null, null);
    SpanSnapshot snapEmpty = nullableSnapshot("", "", "", "");

    AggregateEntry first = table.findOrInsert(snapNull);
    AggregateEntry secondNull = table.findOrInsert(nullableSnapshot(null, null, null, null));
    AggregateEntry forEmpty = table.findOrInsert(snapEmpty);

    assertSame(first, secondNull, "two null-fielded snapshots must hit the same entry");
    assertSame(first, forEmpty, "null- and empty-fielded snapshots must hit the same entry");
    assertEquals(1, table.size());
  }

  @Test
  void nullServiceAndSpanKindDoNotNpeAndCollapseWithEmpty() {
    // Null service and spanKind are accepted (canonicalize to length-zero) and collapse with
    // empty-string variants onto the same entry.
    AggregateTable table = new AggregateTable(8);

    SpanSnapshot allNulls = nullServiceKindSnapshot(null, null);
    SpanSnapshot allEmpty = nullServiceKindSnapshot("", "");

    AggregateEntry first = table.findOrInsert(allNulls);
    AggregateEntry secondNull = table.findOrInsert(nullServiceKindSnapshot(null, null));
    AggregateEntry forEmpty = table.findOrInsert(allEmpty);

    assertSame(first, secondNull, "two null-service/-kind snapshots must hit the same entry");
    assertSame(first, forEmpty, "null- and empty-service/-kind snapshots must hit the same entry");
    assertEquals(1, table.size());
    assertEquals(0, first.getService().length(), "null serviceName should canonicalize to EMPTY");
    assertEquals(0, first.getSpanKind().length(), "null spanKind should canonicalize to EMPTY");
  }

  private static SpanSnapshot nullServiceKindSnapshot(String service, String spanKind) {
    return new SpanSnapshot(
        "resource",
        service,
        "op",
        null,
        "web",
        (short) 200,
        false,
        true,
        spanKind,
        null,
        null,
        null,
        null,
        null,
        null,
        0L);
  }

  private static SpanSnapshot nullableSnapshot(
      String resource, String operation, String type, String serviceNameSource) {
    return new SpanSnapshot(
        resource,
        "svc",
        operation,
        serviceNameSource,
        type,
        (short) 200,
        false,
        true,
        "client",
        null,
        null,
        null,
        null,
        null,
        null,
        0L);
  }

  @Test
  void resetHandlersClearsBlockedCountsAndRefreshesCapacity() {
    // Use limits-enabled handlers injected via the 3-arg constructor to test resetHandlers()
    // without relying on the Config flag being set.
    PropertyHandlers handlers = new PropertyHandlers();
    AggregateTable table = new AggregateTable(512, AdditionalTagsSchema.EMPTY, handlers);

    // Fill the service cardinality budget and push one value over the limit.
    for (int i = 0; i < MetricCardinalityLimits.SERVICE; i++) {
      table.findOrInsert(snapshot("svc-" + i, "op", "client"));
    }
    AggregateEntry blocked = table.findOrInsert(snapshot("svc-overflow", "op", "client"));
    // All overflow services map to the same sentinel bucket.
    AggregateEntry blocked2 = table.findOrInsert(snapshot("svc-overflow-2", "op", "client"));
    assertSame(blocked, blocked2);

    HealthMetrics metrics = mock(HealthMetrics.class);
    table.resetHandlers(metrics);

    verify(metrics).onTagCardinalityBlocked(new String[] {"tag:service"}, 2L);
    verifyNoMoreInteractions(metrics);

    // After reset, a new service name should land in a fresh bucket, not the sentinel.
    AggregateEntry afterReset = table.findOrInsert(snapshot("svc-new", "op", "client"));
    assertNotSame(blocked, afterReset);
    assertEquals("svc-new", afterReset.getService().toString());
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
      int pairCount = namesAndValues.length / 2;
      String[] names = new String[pairCount];
      String[] values = new String[pairCount];
      for (int i = 0; i < pairCount; i++) {
        names[i] = namesAndValues[2 * i];
        values[i] = namesAndValues[2 * i + 1];
      }
      this.peerTagSchema = new PeerTagSchema(names, PeerTagSchema.NO_STATE);
      this.peerTagValues = values;
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
          null,
          tagAndDuration);
    }
  }
}
