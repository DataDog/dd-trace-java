package datadog.trace.core.baggage

import datadog.context.Context
import datadog.trace.api.Config
import datadog.trace.api.metrics.BaggageMetrics
import datadog.trace.api.telemetry.CoreMetricCollector
import spock.lang.Specification

class BaggagePropagatorTelemetryTest extends Specification {

  def "should directly increment baggage metrics"() {
    given:
    def baggageMetrics = BaggageMetrics.getInstance()
    def collector = CoreMetricCollector.getInstance()

    when:
    baggageMetrics.onBaggageInjected()
    collector.prepareMetrics()
    def metrics = collector.drain()

    then:
    def baggageMetric = metrics.find { it.metricName == "context_header_style.injected" }
    baggageMetric != null
    baggageMetric.value >= 1
    baggageMetric.tags.contains("header_style:baggage")
  }

  def "should increment telemetry counter when baggage is successfully extracted"() {
    given:
    def config = Mock(Config) {
      isBaggageExtract() >> true
      isBaggageInject() >> true
      getBaggageMaxItems() >> 64
      getBaggageMaxBytes() >> 8192
    }
    def propagator = new BaggagePropagator(config)
    def context = Context.root()
    def carrier = ["baggage": "key1=value1,key2=value2"]
    def visitor = { map, consumer ->
      map.each { k, v -> consumer.accept(k, v) }
    }
    def collector = CoreMetricCollector.getInstance()

    when:
    propagator.extract(context, carrier, visitor)
    collector.prepareMetrics()
    def metrics = collector.drain()

    then:
    def baggageMetric = metrics.find { it.metricName == "context_header_style.extracted" }
    baggageMetric != null
    baggageMetric.value >= 1
    baggageMetric.tags.contains("header_style:baggage")
  }

  def "should directly increment all baggage metrics"() {
    given:
    def baggageMetrics = BaggageMetrics.getInstance()
    def collector = CoreMetricCollector.getInstance()

    when:
    baggageMetrics.onBaggageInjected()
    baggageMetrics.onBaggageMalformed()
    baggageMetrics.onBaggageTruncatedByByteLimit()
    baggageMetrics.onBaggageTruncatedByItemLimit()
    collector.prepareMetrics()
    def metrics = collector.drain()

    then:
    def injectedMetric = metrics.find { it.metricName == "context_header_style.injected" }
    injectedMetric != null
    injectedMetric.value == 1
    injectedMetric.tags.contains("header_style:baggage")

    def malformedMetric = metrics.find { it.metricName == "context_header_style.malformed" }
    malformedMetric != null
    malformedMetric.value == 1
    malformedMetric.tags.contains("header_style:baggage")

    def bytesTruncatedMetric = metrics.find {
      it.metricName == "context_header_style.truncated" &&
        it.tags.contains("truncation_reason:baggage_byte_count_exceeded")
    }
    bytesTruncatedMetric != null
    bytesTruncatedMetric.value == 1

    def itemsTruncatedMetric = metrics.find {
      it.metricName == "context_header_style.truncated" &&
        it.tags.contains("truncation_reason:baggage_item_count_exceeded")
    }
    itemsTruncatedMetric != null
    itemsTruncatedMetric.value == 1
  }

  def "should not increment telemetry counter when baggage extraction fails"() {
    given:
    def config = Mock(Config) {
      isBaggageExtract() >> true
      isBaggageInject() >> true
      getBaggageMaxItems() >> 64
      getBaggageMaxBytes() >> 8192
    }
    def propagator = new BaggagePropagator(config)
    def context = Context.root()
    def carrier = [:] // No baggage header
    def visitor = { map, consumer ->
      map.each { k, v -> consumer.accept(k, v) }
    }
    def collector = CoreMetricCollector.getInstance()

    when:
    propagator.extract(context, carrier, visitor)
    collector.prepareMetrics()
    def metrics = collector.drain()

    then:
    def foundMetrics = metrics.findAll { it.metricName.startsWith("context_header_style.") }
    foundMetrics.isEmpty() // No extraction occurred, so no metrics should be created
  }
}
