package datadog.telemetry.metric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import datadog.telemetry.TelemetryService;
import datadog.telemetry.api.Metric;
import datadog.trace.api.telemetry.LLMObsMetricCollector;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LLMObsMetricPeriodicActionTest {
  private final LLMObsMetricPeriodicAction periodicAction = new LLMObsMetricPeriodicAction();
  private final LLMObsMetricCollector collector = LLMObsMetricCollector.get();
  private TelemetryService telemetryService;

  @BeforeEach
  void setUp() {
    collector.resetForTesting();
    telemetryService = mock(TelemetryService.class);
  }

  @AfterEach
  void tearDown() {
    collector.resetForTesting();
  }

  @Test
  void emitsOneMetricPerDistinctTagCombination() {
    collector.recordSpanFinished("openai", "llm", true, true, false, true);
    collector.recordSpanFinished("openai", "llm", false, true, false, false);
    collector.recordSpanFinished("anthropic", "embedding", true, false, true, false);

    collector.prepareMetrics();
    periodicAction.doIteration(telemetryService);

    ArgumentCaptor<Metric> captor = forClass(Metric.class);
    verify(telemetryService, times(3)).addMetric(captor.capture());
    verifyNoMoreInteractions(telemetryService);

    HashSet<HashSet<String>> tagSets = new HashSet<>();
    for (Metric metric : captor.getAllValues()) {
      assertEquals("mlobs", metric.getNamespace());
      assertEquals("span.finished", metric.getMetric());
      assertEquals(1, metric.getPoints().size());
      assertEquals(1L, metric.getPoints().get(0).get(1).longValue());
      tagSets.add(new HashSet<>(metric.getTags()));
    }
    assertEquals(
        new HashSet<>(
            Arrays.asList(
                new HashSet<>(
                    Arrays.asList(
                        "integration:openai",
                        "span_kind:llm",
                        "is_root_span:1",
                        "autoinstrumented:1",
                        "error:0",
                        "has_session_id:1")),
                new HashSet<>(
                    Arrays.asList(
                        "integration:openai",
                        "span_kind:llm",
                        "is_root_span:0",
                        "autoinstrumented:1",
                        "error:0",
                        "has_session_id:0")),
                new HashSet<>(
                    Arrays.asList(
                        "integration:anthropic",
                        "span_kind:embedding",
                        "is_root_span:1",
                        "autoinstrumented:0",
                        "error:1",
                        "has_session_id:0")))),
        tagSets);
  }

  /**
   * Identical spans must produce a single point carrying the summed count. Emitting one point of
   * value 1 per span instead loses all but one of the points that share a second-granularity
   * timestamp once they reach the metrics intake.
   */
  @Test
  void emitsASinglePointCarryingTheAggregatedCount() {
    for (int i = 0; i < 5000; i++) {
      collector.recordSpanFinished("openai", "llm", true, true, false, false);
    }

    collector.prepareMetrics();
    periodicAction.doIteration(telemetryService);

    ArgumentCaptor<Metric> captor = forClass(Metric.class);
    verify(telemetryService).addMetric(captor.capture());
    verifyNoMoreInteractions(telemetryService);

    Metric metric = captor.getValue();
    assertEquals(Metric.TypeEnum.COUNT, metric.getType());
    List<List<Number>> points = metric.getPoints();
    assertEquals(1, points.size());
    assertEquals(5000L, points.get(0).get(1).longValue());
  }

  @Test
  void emitsNothingWhenNoSpansFinished() {
    collector.prepareMetrics();
    periodicAction.doIteration(telemetryService);

    verifyNoMoreInteractions(telemetryService);
  }
}
