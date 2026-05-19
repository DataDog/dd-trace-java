package datadog.trace.common.metrics;

import static datadog.trace.common.metrics.AggregateEntry.ERROR_TAG;
import static datadog.trace.common.metrics.AggregateEntry.TOP_LEVEL_TAG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.metrics.agent.AgentMeter;
import datadog.metrics.api.statsd.StatsDClient;
import datadog.metrics.impl.DDSketchHistograms;
import datadog.metrics.impl.MonitoringImpl;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLongArray;
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
  void recordDurationsSumsToTotal() {
    AggregateEntry entry = newEntry();
    entry.recordDurations(3, new AtomicLongArray(new long[] {1L, 2L, 3L}));
    assertEquals(6, entry.getDuration());
  }

  @Test
  void clearResetsAllCounters() {
    AggregateEntry entry = newEntry();
    entry.recordDurations(
        3, new AtomicLongArray(new long[] {5L, ERROR_TAG | 6L, TOP_LEVEL_TAG | 7L}));
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
  void recordDurationsIgnoresTrailingZeros() {
    AggregateEntry entry = newEntry();
    entry.recordDurations(3, new AtomicLongArray(new long[] {1L, 2L, 3L, 0L, 0L, 0L}));
    assertEquals(6, entry.getDuration());
    assertEquals(3, entry.getHitCount());
    assertEquals(0, entry.getErrorCount());
  }

  @Test
  void hitCountIncludesErrors() {
    AggregateEntry entry = newEntry();
    entry.recordDurations(3, new AtomicLongArray(new long[] {1L, 2L, 3L | ERROR_TAG}));
    assertEquals(3, entry.getHitCount());
    assertEquals(1, entry.getErrorCount());
  }

  @Test
  void okAndErrorLatenciesTrackedSeparately() {
    AggregateEntry entry = newEntry();
    entry.recordDurations(
        10,
        new AtomicLongArray(
            new long[] {
              1L, 100L | ERROR_TAG, 2L, 99L | ERROR_TAG, 3L, 98L | ERROR_TAG, 4L, 97L | ERROR_TAG
            }));
    assertTrue(entry.getErrorLatencies().getMaxValue() >= 99);
    assertTrue(entry.getOkLatencies().getMaxValue() <= 5);
  }

  private static AggregateEntry newEntry() {
    SpanSnapshot snapshot =
        new SpanSnapshot(
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
            0L);
    return AggregateEntry.forSnapshot(snapshot);
  }
}
