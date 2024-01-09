package datadog.trace.api.metrics

import datadog.trace.test.util.DDSpecification

class SpanMetricRegistryTest extends DDSpecification {
  def 'test registry not crash if telemetry is disabled'() {
    setup:
    injectSysConfig('instrumentation.telemetry.enabled', telemetryEnabled)

    when:
    def spanMetricRegistry = SpanMetricRegistry.getInstance()
    def spanMetrics = spanMetricRegistry.get('test-api')
    spanMetrics.onSpanCreated()
    spanMetrics.onSpanFinished()

    then:
    noExceptionThrown()

    cleanup:
    if (spanMetricRegistry instanceof SpanMetricRegistryImpl) {
      ((SpanMetricRegistryImpl) spanMetricRegistry).getSpanMetrics().forEach {
        if (it instanceof SpanMetricsImpl) {
          ((SpanMetricsImpl) it).getCounters().forEach {
            it.valueAndReset
          }
        }
      }
    }

    where:
    telemetryEnabled << ['true', 'false']
  }

  def 'test registry API'() {
    setup:
    def spanMetricRegistry = new SpanMetricRegistryImpl()

    when:
    def metrics1 = spanMetricRegistry.get('test1')
    def metrics1Ref = spanMetricRegistry.get('test1')

    then:
    metrics1 == metrics1Ref

    when:
    def metrics2 = spanMetricRegistry.get('test2')
    def metricsCollection = spanMetricRegistry.getSpanMetrics()

    then:
    metricsCollection.size() == 2
    metricsCollection.toSet() == [metrics1, metrics2].toSet()

    when:
    metrics1.onSpanCreated()
    metrics1.onSpanFinished()
    metrics1.onSpanCreated()
    metrics2.onSpanCreated()

    then:
    ((SpanMetricsImpl) metrics1).counters[0].getValueAndReset() == 2
    ((SpanMetricsImpl) metrics1).counters[1].getValueAndReset() == 1
    ((SpanMetricsImpl) metrics2).counters[0].getValueAndReset() == 1
    ((SpanMetricsImpl) metrics2).counters[1].getValueAndReset() == 0

    when:
    metrics1.onSpanFinished()
    metrics2.onSpanCreated()
    metrics2.onSpanCreated()
    metrics2.onSpanCreated()
    metrics2.onSpanCreated()
    metrics2.onSpanCreated()

    then:
    ((SpanMetricsImpl) metrics1).counters[0].getValueAndReset() == 0
    ((SpanMetricsImpl) metrics1).counters[1].getValueAndReset() == 1
    ((SpanMetricsImpl) metrics2).counters[0].getValueAndReset() == 5
    ((SpanMetricsImpl) metrics2).counters[1].getValueAndReset() == 0

    and:
    def summary1 = 'test1: spans_created=2, spans_finished=2\n'
    def summary2 = 'test2: spans_created=6, spans_finished=0\n'

    spanMetricRegistry.summary() =~ /($summary1$summary2)|($summary2$summary1)/
  }
}
