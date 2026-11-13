package datadog.trace.api.metrics;

import static datadog.trace.api.metrics.LogsIntakeMetrics.LOGS_DROPPED;
import static datadog.trace.api.metrics.LogsIntakeMetrics.OVERFULL_BUFFER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.telemetry.CoreMetricCollector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LogsIntakeMetricsTest {

  private final LogsIntakeMetrics metrics = LogsIntakeMetrics.getInstance();
  private final TaggedCounter droppedLogs = metrics.getDroppedLogs();

  @BeforeEach
  void resetDelta() {
    droppedLogs.getValueAndReset();
  }

  @Test
  void countsDroppedLogsSinceTheLastRead() {
    metrics.onLogDropped();
    metrics.onLogDropped();

    assertEquals(LOGS_DROPPED, droppedLogs.getName());
    assertEquals(OVERFULL_BUFFER, droppedLogs.getTag());
    assertEquals(2, droppedLogs.getValueAndReset());
    assertEquals(0, droppedLogs.getValueAndReset());
  }

  @Test
  void isNotCollectedWhenTheLogsIntakeIsDisabled() {
    CoreMetricCollector collector = CoreMetricCollector.getInstance();
    collector.drain();
    metrics.onLogDropped();

    collector.prepareMetrics();

    assertTrue(
        collector.drain().stream().noneMatch(metric -> LOGS_DROPPED.equals(metric.metricName)));
  }
}
