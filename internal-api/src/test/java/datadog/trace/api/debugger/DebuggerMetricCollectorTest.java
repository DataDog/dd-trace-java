package datadog.trace.api.debugger;

import static datadog.trace.api.debugger.DebuggerMetricCollector.DroppedReason.PAYLOAD_TOO_LARGE;
import static datadog.trace.api.debugger.DebuggerMetricCollector.DroppedReason.QUEUE_FULL;
import static datadog.trace.api.debugger.DebuggerMetricCollector.SkippedReason.EVALUATION_TIME_OUT;
import static datadog.trace.api.debugger.DebuggerMetricCollector.SkippedReason.RATE_LIMIT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.debugger.DebuggerMetricCollector.DebuggerMetric;
import java.util.Collection;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DebuggerMetricCollectorTest {

  private final DebuggerMetricCollector collector = DebuggerMetricCollector.get();

  @BeforeEach
  public void clearQueue() {
    collector.drain();
  }

  @Test
  public void drainWithoutRecordsReturnsEmpty() {
    assertEquals(0, collector.drain().size());
  }

  @Test
  public void prepareMetricsWithoutRecordsProducesNoMetric() {
    collector.prepareMetrics();
    assertEquals(0, collector.drain().size());
  }

  @Test
  public void recordEventDroppedSurfacesOnPrepareAndDrain() {
    collector.recordEventDropped(QUEUE_FULL);
    collector.prepareMetrics();

    Collection<DebuggerMetric> metrics = collector.drain();
    assertEquals(1, metrics.size());
    DebuggerMetric metric = metrics.iterator().next();
    assertEquals("live_debugger", metric.namespace);
    assertEquals("events.dropped", metric.metricName);
    assertEquals("count", metric.type);
    assertTrue(metric.common);
    assertEquals(1L, metric.value);
    assertEquals(Collections.singletonList("reason:queueFull"), metric.tags);
  }

  @Test
  public void recordEventSkippedSurfacesOnPrepareAndDrain() {
    collector.recordEventSkipped(EVALUATION_TIME_OUT);
    collector.prepareMetrics();

    Collection<DebuggerMetric> metrics = collector.drain();
    assertEquals(1, metrics.size());
    DebuggerMetric metric = metrics.iterator().next();
    assertEquals("events.skipped", metric.metricName);
    assertEquals(1L, metric.value);
    assertEquals(Collections.singletonList("reason:evaluationTimeOut"), metric.tags);
  }

  @Test
  public void countersAccumulateBeforePrepare() {
    collector.recordEventDropped(QUEUE_FULL);
    collector.recordEventDropped(QUEUE_FULL);
    collector.recordEventDropped(QUEUE_FULL);
    collector.prepareMetrics();

    Collection<DebuggerMetric> metrics = collector.drain();
    assertEquals(1, metrics.size());
    assertEquals(3L, metrics.iterator().next().value);
  }

  @Test
  public void distinctReasonsProduceDistinctMetrics() {
    collector.recordEventDropped(QUEUE_FULL);
    collector.recordEventDropped(PAYLOAD_TOO_LARGE);
    collector.prepareMetrics();

    Collection<DebuggerMetric> metrics = collector.drain();
    assertEquals(2, metrics.size());
    assertTrue(metrics.stream().anyMatch(m -> m.tags.contains("reason:queueFull")));
    assertTrue(metrics.stream().anyMatch(m -> m.tags.contains("reason:payloadTooLarge")));
  }

  @Test
  public void droppedAndSkippedReasonsAreIndependent() {
    collector.recordEventDropped(QUEUE_FULL);
    collector.recordEventSkipped(RATE_LIMIT);
    collector.prepareMetrics();

    Collection<DebuggerMetric> metrics = collector.drain();
    assertEquals(2, metrics.size());
    assertTrue(metrics.stream().anyMatch(m -> m.metricName.equals("events.dropped")));
    assertTrue(metrics.stream().anyMatch(m -> m.metricName.equals("events.skipped")));
  }

  @Test
  public void prepareMetricsResetsCountersAfterDrain() {
    collector.recordEventDropped(QUEUE_FULL);
    collector.prepareMetrics();
    assertEquals(1, collector.drain().size());

    // no new events recorded, so a second prepare/drain cycle should be empty
    collector.prepareMetrics();
    assertEquals(0, collector.drain().size());
  }

  @Test
  public void drainClearsQueue() {
    collector.recordEventDropped(QUEUE_FULL);
    collector.prepareMetrics();
    assertEquals(1, collector.drain().size());
    assertEquals(0, collector.drain().size());
  }
}
