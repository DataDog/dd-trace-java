package datadog.telemetry.metric

import datadog.telemetry.TelemetryService
import datadog.telemetry.api.Metric
import datadog.trace.api.metrics.TelemetryMetrics
import datadog.trace.core.metrics.SpanMetrics
import datadog.trace.core.metrics.SpanMetricsImpl
import spock.lang.Specification

class CoreMetricsPeriodActionTest extends Specification {

  void 'test span metrics with multiple occurrence events'() {
    given:
    final telemetryService = Mock(TelemetryService)
    final action = new CoreMetricsPeriodicAction()
    final SpanMetrics spanMetrics = new SpanMetricsImpl(TelemetryMetrics.getInstance())

    when:
    spanMetrics.onSpanCreated("instr-1")
    spanMetrics.onSpanCreated("instr-2")
    spanMetrics.onSpanCreated("instr-1")
    spanMetrics.onSpanCreated("instr-2")
    spanMetrics.onSpanCreated("instr-2")
    spanMetrics.onSpanFinished("instr-2")
    spanMetrics.onSpanFinished("instr-2")
    spanMetrics.onSpanFinished("instr-2")
    spanMetrics.onSpanFinished("instr-2")

    action.collector().prepareMetrics()
    action.doIteration(telemetryService)

    then:
    1 * telemetryService.addMetric({ Metric metric ->
      assertMetric(metric, 'span_created', 'instr-1', 2)
    })
    1 * telemetryService.addMetric({ Metric metric ->
      assertMetric(metric, 'span_created', 'instr-2', 3)
    })
    1 * telemetryService.addMetric({ Metric metric ->
      assertMetric(metric, 'span_finished', 'instr-2', 4)
    })
  }

  void 'test span metrics with interleaved events'() {
    given:
    final telemetryService = Mock(TelemetryService)
    final action = new CoreMetricsPeriodicAction()
    final SpanMetrics spanMetrics = new SpanMetricsImpl(TelemetryMetrics.getInstance())

    when:
    spanMetrics.onSpanCreated("instr-1")
    spanMetrics.onSpanCreated("instr-2")
    spanMetrics.onSpanCreated("instr-3")
    spanMetrics.onSpanFinished("instr-3")
    spanMetrics.onSpanCreated("instr-a")
    spanMetrics.onSpanCreated("instr-b")
    spanMetrics.onSpanFinished("instr-b")
    spanMetrics.onSpanFinished("instr-2")
    spanMetrics.onSpanFinished("instr-a")
    spanMetrics.onSpanFinished("instr-1")

    action.collector().prepareMetrics()
    action.doIteration(telemetryService)

    then:
    1 * telemetryService.addMetric({ Metric metric ->
      assertMetric(metric, 'span_created', 'instr-1', 1)
    })
    1 * telemetryService.addMetric({ Metric metric ->
      assertMetric(metric, 'span_created', 'instr-2', 1)
    })
    1 * telemetryService.addMetric({ Metric metric ->
      assertMetric(metric, 'span_created', 'instr-3', 1)
    })
    1 * telemetryService.addMetric({ Metric metric ->
      assertMetric(metric, 'span_finished', 'instr-3', 1)
    })
    1 * telemetryService.addMetric({ Metric metric ->
      assertMetric(metric, 'span_created', 'instr-a', 1)
    })
    1 * telemetryService.addMetric({ Metric metric ->
      assertMetric(metric, 'span_created', 'instr-b', 1)
    })
    1 * telemetryService.addMetric({ Metric metric ->
      assertMetric(metric, 'span_finished', 'instr-b', 1)
    })
    1 * telemetryService.addMetric({ Metric metric ->
      assertMetric(metric, 'span_finished', 'instr-2', 1)
    })
    1 * telemetryService.addMetric({ Metric metric ->
      assertMetric(metric, 'span_finished', 'instr-a', 1)
    })
    1 * telemetryService.addMetric({ Metric metric ->
      assertMetric(metric, 'span_finished', 'instr-1', 1)
    })
  }

  void 'test span metrics'() {
    given:
    final telemetryService = Mock(TelemetryService)
    final action = new CoreMetricsPeriodicAction()
    final SpanMetrics spanMetrics = new SpanMetricsImpl(TelemetryMetrics.getInstance())

    when:
    spanMetrics.onSpanCreated("instr-1")
    spanMetrics.onSpanCreated("instr-2")
    spanMetrics.onSpanCreated("instr-3")
    spanMetrics.onSpanFinished("instr-3")
    spanMetrics.onSpanCreated("instr-a")
    spanMetrics.onSpanCreated("instr-b")
    spanMetrics.onSpanFinished("instr-b")
    spanMetrics.onSpanFinished("instr-2")
    spanMetrics.onSpanFinished("instr-a")
    spanMetrics.onSpanFinished("instr-1")

    action.collector().prepareMetrics()
    action.doIteration(telemetryService)

    then:
    1 * telemetryService.addMetric({ Metric metric ->
      assertMetric(metric, 'span_created', 'instr-1', 1)
    })
    1 * telemetryService.addMetric({ Metric metric ->
      assertMetric(metric, 'span_created', 'instr-2', 1)
    })
    1 * telemetryService.addMetric({ Metric metric ->
      assertMetric(metric, 'span_created', 'instr-3', 1)
    })
    1 * telemetryService.addMetric({ Metric metric ->
      assertMetric(metric, 'span_finished', 'instr-3', 1)
    })
    1 * telemetryService.addMetric({ Metric metric ->
      assertMetric(metric, 'span_created', 'instr-a', 1)
    })
    1 * telemetryService.addMetric({ Metric metric ->
      assertMetric(metric, 'span_created', 'instr-b', 1)
    })
    1 * telemetryService.addMetric({ Metric metric ->
      assertMetric(metric, 'span_finished', 'instr-b', 1)
    })
    1 * telemetryService.addMetric({ Metric metric ->
      assertMetric(metric, 'span_finished', 'instr-2', 1)
    })
    1 * telemetryService.addMetric({ Metric metric ->
      assertMetric(metric, 'span_finished', 'instr-a', 1)
    })
    1 * telemetryService.addMetric({ Metric metric ->
      assertMetric(metric, 'span_finished', 'instr-1', 1)
    })
  }

  void assertMetric(Metric metric, String metricName, String instrumentationName, long count) {
    assert metric.namespace == 'tracers'
    assert metric.common
    assert metric.metric == metricName
    assert metric.points.size() == 1
    assert metric.points[0].size() == 2
    assert metric.points[0][1] == count
    assert metric.tags == [instrumentationName]
    assert metric.type == Metric.TypeEnum.COUNT
  }
}
