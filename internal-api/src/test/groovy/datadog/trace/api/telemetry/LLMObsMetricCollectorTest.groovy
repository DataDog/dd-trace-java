package datadog.trace.api.telemetry

import datadog.trace.test.util.DDSpecification

class LLMObsMetricCollectorTest extends DDSpecification {
  LLMObsMetricCollector collector = LLMObsMetricCollector.get()

  void setup() {
    // clear any previous metrics
    collector.drain()
  }

  def "no metrics - drain empty list"() {
    when:
    collector.prepareMetrics()

    then:
    collector.drain().isEmpty()
  }

  def "record and drain span finished metrics"() {
    when:
    collector.recordSpanFinished("openai", "llm", true, true, false, false)
    collector.recordSpanFinished("openai", "llm", false, true, false, true)
    collector.recordSpanFinished("anthropic", "embedding", true, false, true, false)
    collector.prepareMetrics()
    def metrics = collector.drain()

    then:
    metrics.size() == 3

    def metric1 = metrics[0]
    metric1.type == 'count'
    metric1.value == 1
    metric1.namespace == 'mlobs'
    metric1.metricName == 'span.finished'
    metric1.tags.sort() == [
      'integration:openai',
      'span_kind:llm',
      'is_root_span:1',
      'autoinstrumented:1',
      'error:0',
      'has_session_id:0'
    ].sort()

    def metric2 = metrics[1]
    metric2.type == 'count'
    metric2.value == 1
    metric2.namespace == 'mlobs'
    metric2.metricName == 'span.finished'
    metric2.tags.toSet() == [
      'integration:openai',
      'span_kind:llm',
      'is_root_span:0',
      'autoinstrumented:1',
      'error:0',
      'has_session_id:1'
    ].toSet()

    def metric3 = metrics[2]
    metric3.type == 'count'
    metric3.value == 1
    metric3.namespace == 'mlobs'
    metric3.metricName == 'span.finished'
    metric3.tags.toSet() == [
      'integration:anthropic',
      'span_kind:embedding',
      'is_root_span:1',
      'autoinstrumented:0',
      'error:1',
      'has_session_id:0'
    ].toSet()
  }
}

