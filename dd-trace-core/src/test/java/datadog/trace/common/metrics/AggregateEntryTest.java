package datadog.trace.common.metrics;

import static datadog.trace.bootstrap.instrumentation.api.UTF8BytesString.EMPTY;
import static datadog.trace.common.metrics.AggregateEntry.ERROR_TAG;
import static datadog.trace.common.metrics.AggregateEntry.TOP_LEVEL_TAG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.metrics.agent.AgentMeter;
import datadog.metrics.api.statsd.StatsDClient;
import datadog.metrics.impl.DDSketchHistograms;
import datadog.metrics.impl.MonitoringImpl;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AggregateEntryTest {

  @BeforeEach
  void resetCardinalityHandlers() {
    AggregateEntry.resetCardinalityHandlers();
  }

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
  void absentOptionalFieldsResolveToEmptySentinel() {
    // serviceSource / httpMethod / httpEndpoint / grpcStatusCode = null on input -> EMPTY on the
    // entry. EMPTY is the universal "absent" sentinel; SerializingMetricWriter and equality use
    // identity comparison against it.
    AggregateEntry entry = newEntry();
    assertSame(EMPTY, entry.getServiceSource());
    assertSame(EMPTY, entry.getHttpMethod());
    assertSame(EMPTY, entry.getHttpEndpoint());
    assertSame(EMPTY, entry.getGrpcStatusCode());
  }

  @Test
  void presentOptionalFieldsCarryTheirValue() {
    AggregateEntry entry =
        AggregateEntryTestUtils.of(
            "resource",
            "svc",
            "op",
            "src",
            "type",
            200,
            false,
            true,
            "client",
            null,
            "GET",
            "/api/v1/foo",
            "0");
    assertNotSame(EMPTY, entry.getServiceSource());
    assertNotSame(EMPTY, entry.getHttpMethod());
    assertNotSame(EMPTY, entry.getHttpEndpoint());
    assertNotSame(EMPTY, entry.getGrpcStatusCode());
    assertEquals("src", entry.getServiceSource().toString());
    assertEquals("GET", entry.getHttpMethod().toString());
    assertEquals("/api/v1/foo", entry.getHttpEndpoint().toString());
    assertEquals("0", entry.getGrpcStatusCode().toString());
  }

  private static AggregateEntry newEntry() {
    return AggregateEntryTestUtils.of(
        "resource", "svc", "op", null, "type", 200, false, true, "client", null, null, null, null);
  }
}
