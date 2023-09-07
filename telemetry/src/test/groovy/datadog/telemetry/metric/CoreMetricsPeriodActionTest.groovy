package datadog.telemetry.metric

import datadog.telemetry.TelemetryService
import datadog.telemetry.api.Metric
import datadog.trace.api.metrics.SpanMetricRegistry
import datadog.trace.api.metrics.SpanMetrics
import spock.lang.Specification

class CoreMetricsPeriodActionTest extends Specification {

  void 'test span metrics with multiple occurrence events'() {
    given:
    final telemetryService = Mock(TelemetryService)
    final action = new CoreMetricsPeriodicAction()
    final SpanMetricRegistry spanMetricRegistry = SpanMetricRegistry.getInstance()
    final SpanMetrics instr1SpanMetric = spanMetricRegistry.get('instr-1')
    final SpanMetrics instr2SpanMetric = spanMetricRegistry.get('instr-2')

    when:
    instr1SpanMetric.onSpanCreated()
    instr2SpanMetric.onSpanCreated()
    instr1SpanMetric.onSpanCreated()
    instr2SpanMetric.onSpanCreated()
    instr2SpanMetric.onSpanCreated()
    instr2SpanMetric.onSpanFinished()
    instr2SpanMetric.onSpanFinished()
    instr2SpanMetric.onSpanFinished()
    instr2SpanMetric.onSpanFinished()

    action.collector().prepareMetrics()
    action.doIteration(telemetryService)

    then:
    1 * telemetryService.addMetric({ Metric metric ->
      assertMetric(metric, 'spans_created', 'instr-1', 2)
    })
    1 * telemetryService.addMetric({ Metric metric ->
      assertMetric(metric, 'spans_created', 'instr-2', 3)
    })
    1 * telemetryService.addMetric({ Metric metric ->
      assertMetric(metric, 'spans_finished', 'instr-2', 4)
    })
    0 * _
  }

  void 'test span metrics with interleaved events'() {
    given:
    final telemetryService = Mock(TelemetryService)
    final action = new CoreMetricsPeriodicAction()
    final SpanMetricRegistry spanMetricRegistry = SpanMetricRegistry.getInstance()
    final SpanMetrics instr1SpanMetric = spanMetricRegistry.get('instr-1')
    final SpanMetrics instr2SpanMetric = spanMetricRegistry.get('instr-2')
    final SpanMetrics instr3SpanMetric = spanMetricRegistry.get('instr-3')
    final SpanMetrics instrASpanMetric = spanMetricRegistry.get('instr-a')
    final SpanMetrics instrBSpanMetric = spanMetricRegistry.get('instr-b')

    when:
    instr1SpanMetric.onSpanCreated()
    instr2SpanMetric.onSpanCreated()
    instr3SpanMetric.onSpanCreated()
    instr3SpanMetric.onSpanFinished()
    instrASpanMetric.onSpanCreated()
    instrBSpanMetric.onSpanCreated()
    instrBSpanMetric.onSpanFinished()
    instr2SpanMetric.onSpanFinished()
    instrASpanMetric.onSpanFinished()
    instr1SpanMetric.onSpanFinished()

    action.collector().prepareMetrics()
    action.doIteration(telemetryService)

    then:
    1 * telemetryService.addMetric({ Metric metric ->
      assertMetric(metric, 'spans_created', 'instr-1', 1)
    })
    1 * telemetryService.addMetric({ Metric metric ->
      assertMetric(metric, 'spans_created', 'instr-2', 1)
    })
    1 * telemetryService.addMetric({ Metric metric ->
      assertMetric(metric, 'spans_created', 'instr-3', 1)
    })
    1 * telemetryService.addMetric({ Metric metric ->
      assertMetric(metric, 'spans_finished', 'instr-3', 1)
    })
    1 * telemetryService.addMetric({ Metric metric ->
      assertMetric(metric, 'spans_created', 'instr-a', 1)
    })
    1 * telemetryService.addMetric({ Metric metric ->
      assertMetric(metric, 'spans_created', 'instr-b', 1)
    })
    1 * telemetryService.addMetric({ Metric metric ->
      assertMetric(metric, 'spans_finished', 'instr-b', 1)
    })
    1 * telemetryService.addMetric({ Metric metric ->
      assertMetric(metric, 'spans_finished', 'instr-2', 1)
    })
    1 * telemetryService.addMetric({ Metric metric ->
      assertMetric(metric, 'spans_finished', 'instr-a', 1)
    })
    1 * telemetryService.addMetric({ Metric metric ->
      assertMetric(metric, 'spans_finished', 'instr-1', 1)
    })
    0 * _
  }

  void 'test span metrics'() {
    given:
    final telemetryService = Mock(TelemetryService)
    final action = new CoreMetricsPeriodicAction()
    final SpanMetricRegistry spanMetricRegistry = SpanMetricRegistry.getInstance()
    final SpanMetrics instr1SpanMetric = spanMetricRegistry.get('instr-1')
    final SpanMetrics instr2SpanMetric = spanMetricRegistry.get('instr-2')
    final SpanMetrics instr3SpanMetric = spanMetricRegistry.get('instr-3')
    final SpanMetrics instrASpanMetric = spanMetricRegistry.get('instr-a')
    final SpanMetrics instrBSpanMetric = spanMetricRegistry.get('instr-b')

    when:
    instr1SpanMetric.onSpanCreated()
    instr2SpanMetric.onSpanCreated()
    instr3SpanMetric.onSpanCreated()
    instr3SpanMetric.onSpanFinished()
    instrASpanMetric.onSpanCreated()
    instrBSpanMetric.onSpanCreated()
    instrBSpanMetric.onSpanFinished()
    instr2SpanMetric.onSpanFinished()
    instrASpanMetric.onSpanFinished()
    instr1SpanMetric.onSpanFinished()

    action.collector().prepareMetrics()
    action.doIteration(telemetryService)

    then:
    1 * telemetryService.addMetric({ Metric metric ->
      assertMetric(metric, 'spans_created', 'instr-1', 1)
    })
    1 * telemetryService.addMetric({ Metric metric ->
      assertMetric(metric, 'spans_created', 'instr-2', 1)
    })
    1 * telemetryService.addMetric({ Metric metric ->
      assertMetric(metric, 'spans_created', 'instr-3', 1)
    })
    1 * telemetryService.addMetric({ Metric metric ->
      assertMetric(metric, 'spans_finished', 'instr-3', 1)
    })
    1 * telemetryService.addMetric({ Metric metric ->
      assertMetric(metric, 'spans_created', 'instr-a', 1)
    })
    1 * telemetryService.addMetric({ Metric metric ->
      assertMetric(metric, 'spans_created', 'instr-b', 1)
    })
    1 * telemetryService.addMetric({ Metric metric ->
      assertMetric(metric, 'spans_finished', 'instr-b', 1)
    })
    1 * telemetryService.addMetric({ Metric metric ->
      assertMetric(metric, 'spans_finished', 'instr-2', 1)
    })
    1 * telemetryService.addMetric({ Metric metric ->
      assertMetric(metric, 'spans_finished', 'instr-a', 1)
    })
    1 * telemetryService.addMetric({ Metric metric ->
      assertMetric(metric, 'spans_finished', 'instr-1', 1)
    })
    0 * _
  }

  void assertMetric(Metric metric, String metricName, String instrumentationName, long count) {
    assert metric.namespace == 'tracers'
    assert metric.common
    assert metric.metric == metricName
    assert metric.points.size() == 1
    assert metric.points[0].size() == 2
    assert metric.points[0][1] == count
    assert metric.tags == ['integration_name:' + instrumentationName]
    assert metric.type == Metric.TypeEnum.COUNT
  }
}
