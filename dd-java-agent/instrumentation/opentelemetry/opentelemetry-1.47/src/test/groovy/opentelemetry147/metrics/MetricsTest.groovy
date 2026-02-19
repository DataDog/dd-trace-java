package opentelemetry147.metrics

import static io.opentelemetry.api.common.AttributeKey.stringKey

import datadog.opentelemetry.shim.OtelInstrumentationScope
import datadog.opentelemetry.shim.metrics.OtelInstrumentDescriptor
import datadog.opentelemetry.shim.metrics.OtelMeterProvider
import datadog.opentelemetry.shim.metrics.data.OtelDoublePoint
import datadog.opentelemetry.shim.metrics.data.OtelHistogramPoint
import datadog.opentelemetry.shim.metrics.data.OtelLongPoint
import datadog.opentelemetry.shim.metrics.data.OtelPoint
import datadog.opentelemetry.shim.metrics.export.OtelInstrumentVisitor
import datadog.opentelemetry.shim.metrics.export.OtelMeterVisitor
import datadog.opentelemetry.shim.metrics.export.OtelMetricsVisitor
import datadog.trace.agent.test.InstrumentationSpecification
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.common.Attributes
import spock.lang.Shared
import spock.lang.Subject

class MetricsTest extends InstrumentationSpecification {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("dd.integration.opentelemetry-metrics.enabled", "true")
  }

  @Shared
  Attributes someAttributes = Attributes.of(stringKey("some"), "thing")

  @Subject
  def meterProvider = GlobalOpenTelemetry.get().meterProvider as OtelMeterProvider

  @Subject
  def meter = meterProvider.get('test')

  def meterReader = new MeterReader()

  def points = [:]

  def "test long counter"() {
    setup:
    def counter = meter
      .counterBuilder("long-counter")
      .build()

    when:
    counter.add(1)
    counter.add(2, someAttributes)
    meterProvider.collectMetrics(meterReader)

    then:
    points['test:long-counter'] == 1
    points['test:long-counter@[some:thing]'] == 2
  }

  def "test double counter"() {
    setup:
    def counter = meter
      .counterBuilder("double-counter")
      .ofDoubles()
      .build()

    when:
    counter.add(1.2)
    counter.add(3.4, someAttributes)
    meterProvider.collectMetrics(meterReader)

    then:
    points['test:double-counter'] == 1.2
    points['test:double-counter@[some:thing]'] == 3.4
  }

  def "test long up-down counter"() {
    setup:
    def counter = meter
      .upDownCounterBuilder("long-up-down-counter")
      .build()

    when:
    counter.add(1)
    counter.add(2, someAttributes)
    meterProvider.collectMetrics(meterReader)

    then:
    points['test:long-up-down-counter'] == 1
    points['test:long-up-down-counter@[some:thing]'] == 2
  }

  def "test double up-down counter"() {
    setup:
    def counter = meter
      .upDownCounterBuilder("double-up-down-counter")
      .ofDoubles()
      .build()

    when:
    counter.add(1.2)
    counter.add(3.4, someAttributes)
    meterProvider.collectMetrics(meterReader)

    then:
    points['test:double-up-down-counter'] == 1.2
    points['test:double-up-down-counter@[some:thing]'] == 3.4
  }

  def "test long gauge"() {
    setup:
    def counter = meter
      .gaugeBuilder("long-gauge")
      .ofLongs()
      .build()

    when:
    counter.set(1)
    counter.set(2, someAttributes)
    meterProvider.collectMetrics(meterReader)

    then:
    points['test:long-gauge'] == 1
    points['test:long-gauge@[some:thing]'] == 2
  }

  def "test double gauge"() {
    setup:
    def counter = meter
      .gaugeBuilder("double-gauge")
      .build()

    when:
    counter.set(1.2)
    counter.set(3.4, someAttributes)
    meterProvider.collectMetrics(meterReader)

    then:
    points['test:double-gauge'] == 1.2
    points['test:double-gauge@[some:thing]'] == 3.4
  }

  def "test long histogram"() {
    setup:
    def histogram = meter
      .histogramBuilder("long-histogram")
      .ofLongs()
      .build()

    when:
    histogram.record(1)
    histogram.record(24)
    histogram.record(101, someAttributes)
    meterProvider.collectMetrics(meterReader)

    then:
    points['test:long-histogram'] == [2.0, [0.0, 5.0, 10.0, 25.0], [0.0, 1.0, 0.0, 1.0], 25.0]
    points['test:long-histogram@[some:thing]'] == [1.0, [100.0, 250.0], [0.0, 1.0], 101.0]
  }

  def "test double histogram"() {
    setup:
    def histogram = meter
      .histogramBuilder("double-histogram")
      .build()

    when:
    histogram.record(1.2)
    histogram.record(24.5)
    histogram.record(101.2, someAttributes)
    meterProvider.collectMetrics(meterReader)

    then:
    points['test:double-histogram'] == [2.0, [0.0, 5.0, 10.0, 25.0], [0.0, 1.0, 0.0, 1.0], 25.7]
    points['test:double-histogram@[some:thing]'] == [1.0, [100.0, 250.0], [0.0, 1.0], 101.2]
  }

  def "test observable long counter"() {
    setup:
    def observable = meter
      .counterBuilder("observable-long-counter")
      .buildWithCallback {m ->
        m.record(1)
        m.record(2, someAttributes)
      }

    when:
    meterProvider.collectMetrics(meterReader)

    then:
    points['test:observable-long-counter'] == 1
    points['test:observable-long-counter@[some:thing]'] == 2

    cleanup:
    observable.close()
  }

  def "test observable double counter"() {
    setup:
    def observable = meter
      .counterBuilder("observable-double-counter")
      .ofDoubles()
      .buildWithCallback {m ->
        m.record(1.2)
        m.record(3.4, someAttributes)
      }

    when:
    meterProvider.collectMetrics(meterReader)

    then:
    points['test:observable-double-counter'] == 1.2
    points['test:observable-double-counter@[some:thing]'] == 3.4

    cleanup:
    observable.close()
  }

  def "test observable long up-down counter"() {
    setup:
    def observable = meter
      .upDownCounterBuilder("observable-long-up-down-counter")
      .buildWithCallback {m ->
        m.record(1)
        m.record(2, someAttributes)
      }

    when:
    meterProvider.collectMetrics(meterReader)

    then:
    points['test:observable-long-up-down-counter'] == 1
    points['test:observable-long-up-down-counter@[some:thing]'] == 2

    cleanup:
    observable.close()
  }

  def "test observable double up-down counter"() {
    setup:
    def observable = meter
      .upDownCounterBuilder("observable-double-up-down-counter")
      .ofDoubles()
      .buildWithCallback {m ->
        m.record(1.2)
        m.record(3.4, someAttributes)
      }

    when:
    meterProvider.collectMetrics(meterReader)

    then:
    points['test:observable-double-up-down-counter'] == 1.2
    points['test:observable-double-up-down-counter@[some:thing]'] == 3.4

    cleanup:
    observable.close()
  }

  def "test observable long gauge"() {
    setup:
    def observable = meter
      .gaugeBuilder("observable-long-gauge")
      .ofLongs()
      .buildWithCallback {m ->
        m.record(1)
        m.record(2, someAttributes)
      }

    when:
    meterProvider.collectMetrics(meterReader)

    then:
    points['test:observable-long-gauge'] == 1
    points['test:observable-long-gauge@[some:thing]'] == 2

    cleanup:
    observable.close()
  }

  def "test observable double gauge"() {
    setup:
    def observable = meter
      .gaugeBuilder("observable-double-gauge")
      .buildWithCallback {m ->
        m.record(1.2)
        m.record(3.4, someAttributes)
      }

    when:
    meterProvider.collectMetrics(meterReader)

    then:
    points['test:observable-double-gauge'] == 1.2
    points['test:observable-double-gauge@[some:thing]'] == 3.4

    cleanup:
    observable.close()
  }

  def "test batch callback"() {
    setup:
    def longCounterObserver = meter
    .counterBuilder("long-counter-observer")
    .buildObserver()
    def doubleCounterObserver = meter
    .counterBuilder("double-counter-observer")
    .ofDoubles()
    .buildObserver()
    def longUpDownCounterObserver = meter
    .upDownCounterBuilder("long-up-down-counter-observer")
    .buildObserver()
    def doubleUpDownCounterObserver = meter
    .upDownCounterBuilder("double-up-down-counter-observer")
    .ofDoubles()
    .buildObserver()
    def longGaugeObserver = meter
    .gaugeBuilder("long-gauge-observer")
    .ofLongs()
    .buildObserver()
    def doubleGaugeObserver = meter
    .gaugeBuilder("double-gauge-observer")
    .buildObserver()
    def batchCallback = meter
    .batchCallback(() -> {
      longCounterObserver.record(1)
      longCounterObserver.record(10, someAttributes)
      doubleCounterObserver.record(2.3)
      doubleCounterObserver.record(20.3, someAttributes)
      longUpDownCounterObserver.record(4)
      longUpDownCounterObserver.record(40, someAttributes)
      doubleUpDownCounterObserver.record(5.6)
      doubleUpDownCounterObserver.record(50.6, someAttributes)
      longGaugeObserver.record(7)
      longGaugeObserver.record(70, someAttributes)
      doubleGaugeObserver.record(8.9)
      doubleGaugeObserver.record(80.9, someAttributes)
    },
    longCounterObserver,
    doubleCounterObserver,
    longUpDownCounterObserver,
    doubleUpDownCounterObserver,
    longGaugeObserver,
    doubleGaugeObserver)

    // this callback will have no effect because it doesn't declare any measurements
    def noopCallback = meter
    .batchCallback(() -> {
      longCounterObserver.record(1000)
      longCounterObserver.record(1000, someAttributes)
      doubleCounterObserver.record(1000)
      doubleCounterObserver.record(1000, someAttributes)
      longUpDownCounterObserver.record(1000)
      longUpDownCounterObserver.record(1000, someAttributes)
      doubleUpDownCounterObserver.record(1000)
      doubleUpDownCounterObserver.record(1000, someAttributes)
      longGaugeObserver.record(1000)
      longGaugeObserver.record(1000, someAttributes)
      doubleGaugeObserver.record(1000)
      doubleGaugeObserver.record(1000, someAttributes)
    }, null)

    when:
    meterProvider.collectMetrics(meterReader)

    then:
    points['test:long-counter-observer'] == 1
    points['test:long-counter-observer@[some:thing]'] == 10
    points['test:double-counter-observer'] == 2.3
    points['test:double-counter-observer@[some:thing]'] == 20.3
    points['test:long-up-down-counter-observer'] == 4
    points['test:long-up-down-counter-observer@[some:thing]'] == 40
    points['test:double-up-down-counter-observer'] == 5.6
    points['test:double-up-down-counter-observer@[some:thing]'] == 50.6
    points['test:long-gauge-observer'] == 7
    points['test:long-gauge-observer@[some:thing]'] == 70
    points['test:double-gauge-observer'] == 8.9
    points['test:double-gauge-observer@[some:thing]'] == 80.9

    when:
    points.clear()
    // this should invoke batchCallback again
    meterProvider.collectMetrics(meterReader)

    then:
    // delta mode: counters show values added during last collect
    points['test:long-counter-observer'] == 1
    points['test:long-counter-observer@[some:thing]'] == 10
    points['test:double-counter-observer'] == 2.3
    points['test:double-counter-observer@[some:thing]'] == 20.3
    // up-down counters stay cumulative: they show the running total
    points['test:long-up-down-counter-observer'] == 8
    points['test:long-up-down-counter-observer@[some:thing]'] == 80
    points['test:double-up-down-counter-observer'] == 11.2
    points['test:double-up-down-counter-observer@[some:thing]'] == 101.2
    // gauges also stay cumulative: they only show latest value
    points['test:long-gauge-observer'] == 7
    points['test:long-gauge-observer@[some:thing]'] == 70
    points['test:double-gauge-observer'] == 8.9
    points['test:double-gauge-observer@[some:thing]'] == 80.9

    when:
    batchCallback.close()
    points.clear()
    // batchCallback will not be invoked as it it closed
    meterProvider.collectMetrics(meterReader)

    then:
    // delta mode: no values were added as batchCallback is closed
    points['test:long-counter-observer'] == null
    points['test:long-counter-observer@[some:thing]'] == null
    points['test:double-counter-observer'] == null
    points['test:double-counter-observer@[some:thing]'] == null
    // up-down counters stay cumulative: they show the running total
    points['test:long-up-down-counter-observer'] == 8
    points['test:long-up-down-counter-observer@[some:thing]'] == 80
    points['test:double-up-down-counter-observer'] == 11.2
    points['test:double-up-down-counter-observer@[some:thing]'] == 101.2
    // gauges also stay cumulative: they only show latest value
    points['test:long-gauge-observer'] == 7
    points['test:long-gauge-observer@[some:thing]'] == 70
    points['test:double-gauge-observer'] == 8.9
    points['test:double-gauge-observer@[some:thing]'] == 80.9

    cleanup:
    noopCallback.close()
  }

  class MeterReader implements OtelMetricsVisitor, OtelMeterVisitor, OtelInstrumentVisitor {
    def scopeName
    def instrumentName

    @Override
    OtelMeterVisitor visitMeter(OtelInstrumentationScope scope) {
      scopeName = scope.name
      return this
    }

    @Override
    OtelInstrumentVisitor visitInstrument(OtelInstrumentDescriptor descriptor) {
      instrumentName = descriptor.name
      return this
    }

    @Override
    void visitPoint(Attributes attributes, OtelPoint point) {
      def key = scopeName + ':' + instrumentName
      if (!attributes.isEmpty()) {
        key = key + '@' + attributes.asMap()
      }
      switch (point.class) {
        case OtelLongPoint:
        points.put(key, (point as OtelLongPoint).value)
        break
        case OtelDoublePoint:
        points.put(key, (point as OtelDoublePoint).value)
        break
        case OtelHistogramPoint:
        OtelHistogramPoint h = point as OtelHistogramPoint
        points.put(key, [h.count, h.bucketBoundaries, h.bucketCounts, h.sum])
        break
      }
    }
  }
}
