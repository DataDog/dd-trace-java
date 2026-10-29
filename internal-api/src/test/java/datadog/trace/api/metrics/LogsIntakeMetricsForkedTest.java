package datadog.trace.api.metrics;

import static datadog.trace.api.config.GeneralConfig.AGENTLESS_LOG_SUBMISSION_ENABLED;
import static datadog.trace.api.metrics.LogsIntakeMetrics.LOGS_DROPPED;
import static datadog.trace.api.metrics.LogsIntakeMetrics.OVERFULL_BUFFER;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.telemetry.CoreMetricCollector;
import datadog.trace.api.telemetry.CoreMetricCollector.CoreMetric;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.junit.utils.config.WithConfigExtension;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(WithConfigExtension.class)
@WithConfig(key = AGENTLESS_LOG_SUBMISSION_ENABLED, value = "true")
class LogsIntakeMetricsForkedTest {

  @Test
  void reportsDroppedLogsSinceTheLastCollection() {
    LogsIntakeMetrics metrics = LogsIntakeMetrics.getInstance();
    CoreMetricCollector collector = CoreMetricCollector.getInstance();
    collector.drain();

    metrics.onLogDropped();
    metrics.onLogDropped();

    List<CoreMetric> first = collectDroppedLogs(collector);
    assertEquals(1, first.size());
    assertEquals(2L, first.get(0).value.longValue());
    assertEquals(singletonList(OVERFULL_BUFFER), first.get(0).tags);
    assertTrue(collectDroppedLogs(collector).isEmpty());
  }

  private static List<CoreMetric> collectDroppedLogs(CoreMetricCollector collector) {
    collector.prepareMetrics();
    return collector.drain().stream()
        .filter(metric -> LOGS_DROPPED.equals(metric.metricName))
        .collect(toList());
  }
}
