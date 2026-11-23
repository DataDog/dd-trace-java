package datadog.trace.api.telemetry;

import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LLMObsMetricCollectorUserProcessorTest {
  private final LLMObsMetricCollector collector = LLMObsMetricCollector.get();

  @BeforeEach
  void clearMetrics() {
    collector.drain();
  }

  @Test
  void recordsUserProcessorCalledMetrics() {
    collector.recordUserProcessorCalled(false);
    collector.recordUserProcessorCalled(true);

    List<LLMObsMetricCollector.LLMObsMetric> metrics = new ArrayList<>(collector.drain());

    assertEquals(2, metrics.size());
    assertEquals(LLMObsMetricCollector.USER_PROCESSOR_CALLED_METRIC, metrics.get(0).metricName);
    assertEquals(LLMObsMetricCollector.COUNT_METRIC_TYPE, metrics.get(0).type);
    assertEquals(singletonList("error:0"), metrics.get(0).tags);
    assertEquals(LLMObsMetricCollector.USER_PROCESSOR_CALLED_METRIC, metrics.get(1).metricName);
    assertEquals(LLMObsMetricCollector.COUNT_METRIC_TYPE, metrics.get(1).type);
    assertEquals(singletonList("error:1"), metrics.get(1).tags);
  }
}
