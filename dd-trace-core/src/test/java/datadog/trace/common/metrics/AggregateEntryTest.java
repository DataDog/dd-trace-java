package datadog.trace.common.metrics;

import static datadog.trace.common.metrics.AggregateEntry.ERROR_TAG;
import static datadog.trace.common.metrics.AggregateEntry.TOP_LEVEL_TAG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.metrics.agent.AgentMeter;
import datadog.metrics.api.statsd.StatsDClient;
import datadog.metrics.impl.DDSketchHistograms;
import datadog.metrics.impl.MonitoringImpl;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class AggregateEntryTest {

  @BeforeAll
  static void initAgentMeter() {
    // recordOneDuration -> Histogram.accept needs AgentMeter to be initialized.
    MonitoringImpl monitoring = new MonitoringImpl(StatsDClient.NO_OP, 1, TimeUnit.SECONDS);
    AgentMeter.registerIfAbsent(StatsDClient.NO_OP, monitoring, DDSketchHistograms.FACTORY);
    monitoring.newTimer("test.init");
  }

  @Test
  void recordOneDurationSumsToTotal() {
    AggregateEntry entry = newEntry();
    entry.recordOneDuration(1L);
    entry.recordOneDuration(2L);
    entry.recordOneDuration(3L);
    assertEquals(6, entry.getDuration());
  }

  @Test
  void clearResetsAllCounters() {
    AggregateEntry entry = newEntry();
    entry.recordOneDuration(5L);
    entry.recordOneDuration(ERROR_TAG | 6L);
    entry.recordOneDuration(TOP_LEVEL_TAG | 7L);
    entry.clear();
    assertEquals(0, entry.getDuration());
    assertEquals(0, entry.getErrorCount());
    assertEquals(0, entry.getTopLevelCount());
    assertEquals(0, entry.getHitCount());
  }

  @Test
  void recordOneDurationAccumulatesOkErrorAndTopLevel() {
    AggregateEntry entry = newEntry();
    entry.recordOneDuration(10L);
    entry.recordOneDuration(10L | TOP_LEVEL_TAG);
    entry.recordOneDuration(10L | ERROR_TAG);

    assertEquals(3, entry.getHitCount());
    assertEquals(30, entry.getDuration());
    assertEquals(1, entry.getErrorCount());
    assertEquals(1, entry.getTopLevelCount());
  }

  @Test
  void hitCountIncludesErrors() {
    AggregateEntry entry = newEntry();
    entry.recordOneDuration(1L);
    entry.recordOneDuration(2L);
    entry.recordOneDuration(3L | ERROR_TAG);
    assertEquals(3, entry.getHitCount());
    assertEquals(1, entry.getErrorCount());
  }

  @Test
  void okAndErrorLatenciesTrackedSeparately() {
    AggregateEntry entry = newEntry();
    long[] durations = {
      1L, 100L | ERROR_TAG, 2L, 99L | ERROR_TAG, 3L, 98L | ERROR_TAG, 4L, 97L | ERROR_TAG
    };
    for (long d : durations) {
      entry.recordOneDuration(d);
    }
    assertTrue(entry.getErrorLatencies().getMaxValue() >= 99);
    assertTrue(entry.getOkLatencies().getMaxValue() <= 5);
  }

  @Test
  void testUtilsEqualsIsConsistentWithHashCodeAcrossDifferentSchemaLayouts() {
    // Contract test for AggregateEntryTestUtils (the test-side equality helper used by Spock
    // mock matchers). Production AggregateEntry has no equals override.
    //
    // Two entries with identical encoded peerTags but different raw layouts must not be equal,
    // because hashOf folds in the raw arrays. Equality on the encoded list would let them
    // collapse while their hashCodes differ -- violating the contract.
    //
    //   A: schema ["a","b"], values [null,"x"] -> encoded ["b:x"]
    //   B: schema ["b","c"], values ["x",null] -> encoded ["b:x"]
    AggregateEntry a =
        AggregateEntryTestUtils.forSnapshot(
            snapshotWithPeerTags(new String[] {"a", "b"}, new String[] {null, "x"}));
    AggregateEntry b =
        AggregateEntryTestUtils.forSnapshot(
            snapshotWithPeerTags(new String[] {"b", "c"}, new String[] {"x", null}));

    // Sanity: same encoded peer tags, despite different raw layout.
    assertEquals(a.getPeerTags(), b.getPeerTags());

    // Different raw layouts -> entries must not be equal via the test helper.
    assertFalse(AggregateEntryTestUtils.equals(a, b));
    // And different hashCodes (matching the inequality).
    assertNotEquals(AggregateEntryTestUtils.hashCode(a), AggregateEntryTestUtils.hashCode(b));
  }

  @Test
  void testUtilsEqualEntriesHaveEqualHashCodes() {
    AggregateEntry a =
        AggregateEntryTestUtils.forSnapshot(
            snapshotWithPeerTags(new String[] {"a", "b"}, new String[] {null, "x"}));
    AggregateEntry b =
        AggregateEntryTestUtils.forSnapshot(
            snapshotWithPeerTags(new String[] {"a", "b"}, new String[] {null, "x"}));

    assertTrue(AggregateEntryTestUtils.equals(a, b));
    assertEquals(AggregateEntryTestUtils.hashCode(a), AggregateEntryTestUtils.hashCode(b));
  }

  private static SpanSnapshot snapshotWithPeerTags(String[] names, String[] values) {
    return AggregateEntryTestUtils.buildSnapshot(
        "resource",
        "svc",
        "op",
        null,
        "type",
        (short) 200,
        false,
        true,
        "client",
        PeerTagSchema.testSchema(names),
        values,
        null,
        null,
        null,
        0L);
  }

  private static AggregateEntry newEntry() {
    SpanSnapshot snapshot =
        AggregateEntryTestUtils.buildSnapshot(
            "resource",
            "svc",
            "op",
            null,
            "type",
            (short) 200,
            false,
            true,
            "client",
            null,
            null,
            null,
            null,
            null,
            0L);
    return AggregateEntryTestUtils.forSnapshot(snapshot);
  }
}
