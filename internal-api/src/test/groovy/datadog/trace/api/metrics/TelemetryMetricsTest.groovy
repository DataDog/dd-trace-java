package datadog.trace.api.metrics

import datadog.trace.test.util.DDSpecification

import java.util.function.Supplier

class TelemetryMetricsTest extends DDSpecification {

  def 'check api would never crash'() {
    setup:
    injectSysConfig('instrumentation.telemetry.enabled', telemetryEnabled)

    when:
    def metrics = Metrics.getInstance()

    then:
    metrics != null

    when:
    def counter = metrics.createCounter('counter', true)
    counter.increment()
    counter.increment(2L)

    Supplier<Double> s = () -> 3D
    metrics.createGauge('gauge', s, true)

    def meter = metrics.createMeter('meter', true)
    meter.mark()
    meter.mark(4D)

    then:
    noExceptionThrown()

    where:
    telemetryEnabled << ['true', 'false']
  }
}
