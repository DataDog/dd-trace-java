package datadog.telemetry.metric;

import static datadog.trace.api.featureflag.flagevaluation.FlagEvaluationMetrics.Metric.DEGRADED_CARDINALITY_CAP;
import static datadog.trace.api.featureflag.flagevaluation.FlagEvaluationMetrics.Metric.DROPPED_CLOSED;
import static datadog.trace.api.featureflag.flagevaluation.FlagEvaluationMetrics.Metric.PAYLOAD_SPLITS;
import static datadog.trace.api.telemetry.MetricCollector.RAW_QUEUE_SIZE;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import datadog.telemetry.TelemetryService;
import datadog.telemetry.api.Metric;
import datadog.trace.api.featureflag.flagevaluation.FlagEvaluationMetrics;
import datadog.trace.api.telemetry.CoreMetricCollector;
import datadog.trace.api.telemetry.MetricCollector;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.tabletest.junit.TableTest;

class FlagEvaluationMetricPeriodicActionTest {
  private final FlagEvaluationMetrics metrics = FlagEvaluationMetrics.getInstance();
  private final FlagEvaluationMetricPeriodicAction action =
      new FlagEvaluationMetricPeriodicAction();
  private final TelemetryService service = mock(TelemetryService.class);

  @BeforeEach
  @AfterEach
  void resetMetrics() {
    metrics.drain();
  }

  @Test
  void defaultActionDrainsProductCountersIndependentlyOfCoreMetrics() {
    metrics.count(DROPPED_CLOSED, 3);
    CoreMetricCollector core = CoreMetricCollector.getInstance();
    core.prepareMetrics();
    assertTrue(
        core.drain().stream().noneMatch(metric -> metric.metricName.startsWith("flagevaluation.")));

    action.collector().prepareMetrics();
    action.doIteration(service);
    ArgumentCaptor<Metric> captor = forClass(Metric.class);
    verify(service).addMetric(captor.capture());
    assertEquals("flagevaluation.rows.dropped", captor.getValue().getMetric());
    assertEquals(3L, captor.getValue().getPoints().get(0).get(1).longValue());
  }

  @TableTest({
    "Scenario         | Counter                  | Name                          | Tag                   ",
    "queue overflow   | DROPPED_QUEUE_OVERFLOW   | flagevaluation.rows.dropped   | reason:queue_overflow ",
    "closed           | DROPPED_CLOSED           | flagevaluation.rows.dropped   | reason:closed         ",
    "degraded cap     | DROPPED_DEGRADED_CAP     | flagevaluation.rows.dropped   | reason:degraded_cap   ",
    "payload drop     | DROPPED_PAYLOAD_LIMIT    | flagevaluation.rows.dropped   | reason:payload_limit  ",
    "cardinality cap  | DEGRADED_CARDINALITY_CAP | flagevaluation.rows.degraded  | reason:cardinality_cap",
    "payload degraded | DEGRADED_PAYLOAD_LIMIT   | flagevaluation.rows.degraded  | reason:payload_limit  ",
    "split            | PAYLOAD_SPLITS           | flagevaluation.payload.splits |                       "
  })
  void preservesWireFormatAcrossCollectionIntervals(
      FlagEvaluationMetrics.Metric counter, String name, String tag) {
    metrics.count(counter, 2);
    metrics.count(counter, 3);
    assertTrue(action.collector().drain().isEmpty());
    action.collector().prepareMetrics();
    metrics.count(counter, 7);
    action.collector().prepareMetrics();
    action.doIteration(service);

    ArgumentCaptor<Metric> captor = forClass(Metric.class);
    verify(service).addMetric(captor.capture());
    Metric metric = captor.getValue();
    assertEquals(name, metric.getMetric());
    assertEquals("tracers", metric.getNamespace());
    assertEquals(true, metric.getCommon());
    assertEquals(Metric.TypeEnum.COUNT, metric.getType());
    assertEquals(tag == null ? emptyList() : singletonList(tag), metric.getTags());
    assertEquals(2, metric.getPoints().size());
    assertEquals(5L, metric.getPoints().get(0).get(1).longValue());
    assertEquals(7L, metric.getPoints().get(1).get(1).longValue());
    action.collector().prepareMetrics();
    action.doIteration(service);
    verifyNoMoreInteractions(service);
  }

  @Test
  void preservesContextTruncationTags() {
    metrics.countContextTruncated("max_key_length,max_value_length", 3);
    action.collector().prepareMetrics();
    action.doIteration(service);

    ArgumentCaptor<Metric> captor = forClass(Metric.class);
    verify(service).addMetric(captor.capture());
    Metric metric = captor.getValue();
    assertEquals("flagevaluation.context.truncated", metric.getMetric());
    assertEquals(singletonList("reason:max_key_length,max_value_length"), metric.getTags());
    assertEquals(3L, metric.getPoints().get(0).get(1).longValue());
  }

  @Test
  void fullQueueRetainsSnapshotRemainderAndSubsequentProducerUpdates() {
    MetricCollector<MetricCollector.Metric> collector = action.collector();
    for (int i = 0; i < RAW_QUEUE_SIZE - 1; i++) {
      metrics.count(PAYLOAD_SPLITS, 1);
      collector.prepareMetrics();
    }
    metrics.count(DROPPED_CLOSED, 5);
    metrics.count(DEGRADED_CARDINALITY_CAP, 7);
    metrics.countContextTruncated("max_depth", 3);
    collector.prepareMetrics();
    metrics.count(DROPPED_CLOSED, 11);
    collector.prepareMetrics();

    List<MetricCollector.Metric> reported = new ArrayList<>(collector.drain());
    assertEquals(RAW_QUEUE_SIZE, reported.size());
    collector.prepareMetrics();
    Collection<MetricCollector.Metric> remainder = collector.drain();
    assertEquals(2, remainder.size());
    reported.addAll(remainder);
    collector.prepareMetrics();
    reported.addAll(collector.drain());

    assertEquals(RAW_QUEUE_SIZE - 1, sum(reported, "flagevaluation.payload.splits"));
    assertEquals(16, sum(reported, "flagevaluation.rows.dropped"));
    assertEquals(7, sum(reported, "flagevaluation.rows.degraded"));
    assertEquals(3, sum(reported, "flagevaluation.context.truncated"));
    collector.prepareMetrics();
    assertTrue(collector.drain().isEmpty());
    assertTrue(metrics.drain().isEmpty());
  }

  private static long sum(List<MetricCollector.Metric> metrics, String name) {
    return metrics.stream()
        .filter(metric -> name.equals(metric.metricName))
        .mapToLong(metric -> metric.value.longValue())
        .sum();
  }
}
