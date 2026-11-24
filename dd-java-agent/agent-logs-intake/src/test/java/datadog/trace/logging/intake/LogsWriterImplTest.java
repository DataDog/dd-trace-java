package datadog.trace.logging.intake;

import static java.time.Duration.ofMillis;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import datadog.trace.api.Config;
import datadog.trace.api.intake.Intake;
import datadog.trace.api.metrics.CoreCounter;
import datadog.trace.api.metrics.LogsIntakeMetrics;
import java.util.HashMap;
import org.junit.jupiter.api.Test;

class LogsWriterImplTest {

  @Test
  void dropsMessagesWithoutBlockingWhenTheQueueIsFull() {
    Config config = Config.get();
    LogsWriterImpl writer = new LogsWriterImpl(config, null, Intake.LOGS);
    int messages = config.getAgentlessLogSubmissionQueueSize() + 3;
    CoreCounter droppedLogs = LogsIntakeMetrics.getInstance().getDroppedLogs();
    droppedLogs.getValueAndReset();

    assertTimeout(
        ofMillis(500),
        () -> {
          for (int i = 0; i < messages; i++) {
            writer.log(new HashMap<>());
          }
        });

    assertEquals(3, droppedLogs.getValueAndReset());
  }
}
