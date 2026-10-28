package datadog.trace.logging.intake;

import static datadog.trace.logging.intake.LogsWriterImpl.DROPPED_LOGS_METRIC;
import static datadog.trace.logging.intake.LogsWriterImpl.QUEUE_FULL_REASON;
import static java.time.Duration.ofMillis;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import datadog.trace.api.Config;
import datadog.trace.api.intake.Intake;
import datadog.trace.api.telemetry.CoreMetricCollector;
import datadog.trace.api.telemetry.CoreMetricCollector.CoreMetric;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class LogsWriterImplTest {

  @Test
  void dropsMessagesWithoutBlockingWhenTheQueueIsFull() {
    Config config = Config.get();
    LogsWriterImpl writer = new LogsWriterImpl(config, null, Intake.LOGS);
    int messages = config.getAgentlessLogSubmissionQueueSize() + 3;
    CoreMetricCollector.getInstance().drain();

    assertTimeout(
        ofMillis(500),
        () -> {
          for (int i = 0; i < messages; i++) {
            writer.log(new HashMap<>());
          }
        });
    writer.reportDroppedLogs();
    writer.reportDroppedLogs();

    List<CoreMetric> dropped =
        CoreMetricCollector.getInstance().drain().stream()
            .filter(metric -> DROPPED_LOGS_METRIC.equals(metric.metricName))
            .collect(toList());
    assertEquals(1, dropped.size());
    assertEquals(3L, dropped.get(0).value.longValue());
    assertEquals(singletonList(QUEUE_FULL_REASON), dropped.get(0).tags);
  }
}
