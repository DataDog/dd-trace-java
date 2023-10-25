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
  }
}
