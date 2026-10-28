package datadog.trace.api.metrics;

import static datadog.trace.api.metrics.LogsIntakeMetrics.LOGS_DROPPED;
import static datadog.trace.api.metrics.LogsIntakeMetrics.OVERFULL_BUFFER;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.telemetry.CoreMetricCollector;
import datadog.trace.api.telemetry.CoreMetricCollector.CoreMetric;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LogsIntakeMetricsTest {

  private final LogsIntakeMetrics metrics = LogsIntakeMetrics.getInstance();
  private final CoreMetricCollector collector = CoreMetricCollector.getInstance();

  @BeforeEach
  void resetDeltas() {
    metrics.getDroppedLogs().getValueAndReset();
    collector.drain();
  }

  @Test
  void reportsDroppedLogsSinceTheLastCollection() {
    metrics.onLogDropped();
    metrics.onLogDropped();

    List<CoreMetric> first = collectDroppedLogs();
    assertEquals(1, first.size());
    assertEquals(2L, first.get(0).value.longValue());
    assertEquals(singletonList(OVERFULL_BUFFER), first.get(0).tags);

    metrics.onLogDropped();

    List<CoreMetric> second = collectDroppedLogs();
    assertEquals(1, second.size());
    assertEquals(1L, second.get(0).value.longValue());
  }

  @Test
  void reportsNothingWithoutDroppedLogs() {
    assertTrue(collectDroppedLogs().isEmpty());
  }

  private List<CoreMetric> collectDroppedLogs() {
    collector.prepareMetrics();
    return collector.drain().stream()
        .filter(metric -> LOGS_DROPPED.equals(metric.metricName))
        .collect(toList());
  }
}
