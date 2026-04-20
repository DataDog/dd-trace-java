package datadog.telemetry.metric

import datadog.telemetry.TelemetryService
import datadog.telemetry.api.Metric
import datadog.trace.api.telemetry.LLMObsMetricCollector
import datadog.trace.test.util.DDSpecification

class LLMObsMetricPeriodicActionTest extends DDSpecification {
  LLMObsMetricPeriodicAction periodicAction = new LLMObsMetricPeriodicAction()
  TelemetryService telemetryService = Mock()
  LLMObsMetricCollector collector = LLMObsMetricCollector.get()

  void setup() {
    // clear any previous metrics
    collector.drain()
  }

  void 'test multiple span finished metrics with different tags'() {
    when:
    collector.recordSpanFinished('openai', 'llm', true, true, false, true)
    collector.recordSpanFinished('openai', 'llm', false, true, false, false)
    collector.recordSpanFinished('anthropic', 'embedding', true, false, true, false)
    periodicAction.doIteration(telemetryService)

    then:
    1 * telemetryService.addMetric({ Metric metric ->
      metric.namespace == 'mlobs' &&
        metric.metric == 'span.finished' &&
        metric.tags.toSet() == [
          'integration:openai',
          'span_kind:llm',
          'is_root_span:1',
          'autoinstrumented:1',
          'error:0',
          'has_session_id:1'
        ].toSet()
    })
    1 * telemetryService.addMetric({ Metric metric ->
      metric.namespace == 'mlobs' &&
        metric.metric == 'span.finished' &&
        metric.tags.toSet() == [
          'integration:openai',
          'span_kind:llm',
          'is_root_span:0',
          'autoinstrumented:1',
          'error:0',
          'has_session_id:0'
        ].toSet()
    })
    1 * telemetryService.addMetric({ Metric metric ->
      metric.namespace == 'mlobs' &&
        metric.metric == 'span.finished' &&
        metric.tags.toSet() == [
          'integration:anthropic',
          'span_kind:embedding',
          'is_root_span:1',
          'autoinstrumented:0',
          'error:1',
          'has_session_id:0'
        ].toSet()
    })
    0 * _
  }

  void 'test aggregation of identical metrics'() {
    when:
    collector.recordSpanFinished('openai', 'llm', true, true, false, false)
    collector.recordSpanFinished('openai', 'llm', true, true, false, false)
    collector.recordSpanFinished('openai', 'llm', true, true, false, false)
    periodicAction.doIteration(telemetryService)

    then:
    1 * telemetryService.addMetric({ Metric metric ->
      metric.namespace == 'mlobs' &&
        metric.metric == 'span.finished' &&
        metric.points.size() == 3 &&
        metric.points.every { it[1] == 1 } &&
        metric.tags.toSet() == [
          'integration:openai',
          'span_kind:llm',
          'is_root_span:1',
          'autoinstrumented:1',
          'error:0',
          'has_session_id:0'
        ].toSet()
    })
    0 * _
  }
}

