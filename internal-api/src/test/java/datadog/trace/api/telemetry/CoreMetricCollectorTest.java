package datadog.trace.api.telemetry;

import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.api.metrics.SpanMetricRegistryImpl;
import datadog.trace.api.metrics.SpanMetrics;
import datadog.trace.api.telemetry.CoreMetricCollector.CoreMetric;
import datadog.trace.test.util.DDJavaSpecification;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CoreMetricCollectorTest extends DDJavaSpecification {

  /** Flushes metrics left over by previous tests, as the collector and registry are singletons. */
  @BeforeEach
  void drainPendingMetrics() {
    CoreMetricCollector collector = CoreMetricCollector.getInstance();
    do {
      collector.prepareMetrics();
    } while (!collector.drain().isEmpty());
  }

  @Test
  void updateDrainSpanCoreMetrics() {
    // setup
    SpanMetrics spanMetrics = SpanMetricRegistryImpl.getInstance().get("datadog");
    spanMetrics.onSpanCreated();
    spanMetrics.onSpanCreated();
    spanMetrics.onSpanFinished();
    CoreMetricCollector collector = CoreMetricCollector.getInstance();

    // when
    collector.prepareMetrics();
    List<CoreMetric> metrics = new ArrayList<>(collector.drain());

    // then
    assertEquals(2, metrics.size());

    CoreMetric spanCreatedMetric = metrics.get(0);
    assertEquals("count", spanCreatedMetric.type);
    assertEquals(2L, spanCreatedMetric.value.longValue());
    assertEquals("tracers", spanCreatedMetric.namespace);
    assertEquals("spans_created", spanCreatedMetric.metricName);
    assertEquals(singletonList("integration_name:datadog"), spanCreatedMetric.tags);

    CoreMetric spanFinishedMetric = metrics.get(1);
    assertEquals("count", spanFinishedMetric.type);
    assertEquals(1L, spanFinishedMetric.value.longValue());
    assertEquals("tracers", spanFinishedMetric.namespace);
    assertEquals("spans_finished", spanFinishedMetric.metricName);
    assertEquals(singletonList("integration_name:datadog"), spanFinishedMetric.tags);
  }

  @Test
  void overflowingCoreMetrics() {
    // setup
    SpanMetricRegistryImpl registry = SpanMetricRegistryImpl.getInstance();
    CoreMetricCollector collector = CoreMetricCollector.getInstance();
    int limit = 1024;

    // when
    assertDoesNotThrow(() -> {
      for (int index = 0; index <= limit * 2; index++) {
        SpanMetrics spanMetrics = registry.get("instr-" + index);
        spanMetrics.onSpanCreated();
        spanMetrics.onSpanCreated();
        spanMetrics.onSpanFinished();
      }
    });

    // then
    collector.prepareMetrics();
    assertEquals(limit, collector.drain().size());
  }

  @Test
  void directCountCoreMetric() {
    // setup
    CoreMetricCollector collector = CoreMetricCollector.getInstance();

    // when
    collector.count("flagevaluation.rows.dropped", 3, "reason:queue_overflow");
    List<CoreMetric> metrics = new ArrayList<>(collector.drain());

    // then
    assertEquals(1, metrics.size());

    CoreMetric metric = metrics.get(0);
    assertEquals("count", metric.type);
    assertEquals(3L, metric.value.longValue());
    assertEquals("tracers", metric.namespace);
    assertEquals("flagevaluation.rows.dropped", metric.metricName);
    assertEquals(singletonList("reason:queue_overflow"), metric.tags);
  }
}
