package datadog.trace.api.metrics

import datadog.trace.test.util.DDSpecification

import java.util.function.Supplier

import static datadog.trace.api.metrics.MetricName.named

class CoreMetricsTest extends DDSpecification {
  def 'check api would never crash'() {
    setup:
    injectSysConfig('instrumentation.telemetry.enabled', telemetryEnabled)

    when:
    def metrics = Metrics.getInstance()

    then:
    metrics != null

    when:
    def counter = metrics.createCounter(named('test', true, 'counter'))
    counter.increment()
    counter.increment(2L)

    Supplier<Double> s = () -> 3D
    metrics.createGauge(named('test', true, 'gauge'), s)

    def meter = metrics.createMeter(named('test', true, 'meter'))
    meter.mark()
    meter.mark(4D)

    then:
    noExceptionThrown()

    where:
    telemetryEnabled << ['true', 'false']
  }

  def 'check counter'() {
    setup:
    def metrics = new CoreMetrics()

    when:
    def counter = metrics.createCounter(named('test', true, 'counter'))
    then:
    assert counter.name == 'counter'
    assert counter.common
    assert counter.type == 'COUNT'
    assert counter.value.longValue() == 0

    when:
    counter.increment()
    then:
    counter.value.longValue() == 1

    when:
    counter.increment(10)
    then:
    counter.value.longValue() == 11

    when:
    counter.reset()
    then:
    counter.value.longValue() == 0

    when:
    counter.increment(3)
    then:
    counter.value.longValue() == 3

    when:
    counter.increment(0)
    then:
    counter.value.longValue() == 3

    when:
    counter.increment(-3)
    then:
    thrown(IllegalArgumentException)
    counter.value.longValue() == 3
  }

  def 'check gauge'() {
    setup:
    def metrics = new CoreMetrics()
    Supplier<Long> supplier = new Supplier<Long>() {
      int value = 1
      @Override
      Long get() {
        return value*= 2
      }
    }

    when:
    def gauge = metrics.createGauge(named('test', true, 'gauge'), supplier)
    then:
    assert gauge.name == 'gauge'
    assert gauge.common
    assert gauge.type == 'GAUGE'
    assert gauge.value.longValue() == 2
    assert gauge.value.longValue() == 4
    gauge.reset()
    assert gauge.value.longValue() == 8
  }

  def 'check meter'() {
    setup:
    def metrics = new CoreMetrics()

    when:
    def meter = metrics.createMeter(named('test', true, 'meter'))
    then:
    assert meter.name == 'meter'
    assert meter.common
    assert meter.type == 'RATE'
    assert meter.value.longValue() == 0

    when:
    meter.mark()
    then:
    meter.value.longValue() == 1

    when:
    meter.mark(10)
    then:
    meter.value.longValue() == 11

    when:
    meter.reset()
    then:
    meter.value.longValue() == 0

    when:
    meter.mark(3)
    then:
    meter.value.longValue() == 3
  }

  def 'check instrument update tracking'() {
    setup:
    def metrics = new CoreMetrics()
    def counter = metrics.createCounter(named('test', true, 'counter'))

    // Check no updated instrument by default
    when:
    def iterator = metrics.updatedInstruments()
    then:
    !iterator.hasNext()

    // Check one instrument update
    when:
    counter.increment()
    iterator = metrics.updatedInstruments()
    then:
    iterator.hasNext()
    when:
    iterator.next()
    then:
    !iterator.hasNext()

    // Check instrument is still updated if not reset
    when:
    iterator = metrics.updatedInstruments()
    then:
    iterator.hasNext()
    when:
    iterator.next()
    then:
    !iterator.hasNext()

    // Check reset instrument are not updated
    when:
    iterator = metrics.updatedInstruments()
    iterator.next().reset()
    iterator = metrics.updatedInstruments()
    then:
    !iterator.hasNext()
  }
}
