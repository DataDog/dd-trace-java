package datadog.telemetry.metric;

import static datadog.trace.api.telemetry.FlagEvaluationMetricCollector.Counter.DROPPED_CLOSED;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import datadog.telemetry.TelemetryService;
import datadog.telemetry.api.Metric;
import datadog.trace.api.telemetry.FlagEvaluationMetricCollector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FlagEvaluationMetricPeriodicActionTest {
  private final FlagEvaluationMetricCollector collector = FlagEvaluationMetricCollector.get();
  private final FlagEvaluationMetricPeriodicAction action =
      new FlagEvaluationMetricPeriodicAction();
  private final TelemetryService service = mock(TelemetryService.class);

  @BeforeEach
  @AfterEach
  void resetCollector() {
    collector.resetForTesting();
  }

  @Test
  void emitsFlagEvaluationMetricsFromTheDedicatedCollector() {
    assertSame(collector, action.collector());
    collector.count(DROPPED_CLOSED, 3);
    collector.prepareMetrics();

    action.doIteration(service);

    ArgumentCaptor<Metric> captor = forClass(Metric.class);
    verify(service).addMetric(captor.capture());
    verifyNoMoreInteractions(service);
    Metric metric = captor.getValue();
    assertEquals("flagevaluation.rows.dropped", metric.getMetric());
    assertEquals("tracers", metric.getNamespace());
    assertEquals(true, metric.getCommon());
    assertEquals(Metric.TypeEnum.COUNT, metric.getType());
    assertEquals(singletonList("reason:closed"), metric.getTags());
    assertEquals(3L, metric.getPoints().get(0).get(1).longValue());
  }
}
