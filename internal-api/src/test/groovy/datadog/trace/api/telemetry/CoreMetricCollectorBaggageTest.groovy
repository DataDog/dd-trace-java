package datadog.trace.api.telemetry

import datadog.trace.api.metrics.BaggageMetrics
import spock.lang.Specification

class CoreMetricCollectorBaggageTest extends Specification {

  def "should collect baggage extraction metrics with header_style tag"() {
    given:
    def collector = CoreMetricCollector.getInstance()
    def baggageMetrics = BaggageMetrics.getInstance()

    when:
    baggageMetrics.onBaggageExtracted()
    baggageMetrics.onBaggageExtracted()
    collector.prepareMetrics()
    def metrics = collector.drain()

    then:
    def baggageMetric = metrics.find { it.metricName == "context_header_style.extracted" }
    baggageMetric != null
    baggageMetric.namespace == "tracers"
    baggageMetric.type == "count"
    baggageMetric.value == 2
    baggageMetric.tags.contains("header_style:baggage")
    baggageMetric.common == true
  }

  def "should collect baggage injection metrics with header_style tag"() {
    given:
    def collector = CoreMetricCollector.getInstance()
    def baggageMetrics = BaggageMetrics.getInstance()

    when:
    baggageMetrics.onBaggageInjected()
    collector.prepareMetrics()
    def metrics = collector.drain()

    then:
    def baggageMetric = metrics.find { it.metricName == "context_header_style.injected" }
    baggageMetric != null
    baggageMetric.tags.contains("header_style:baggage")
    baggageMetric.value == 1
  }

  def "should collect baggage malformed metrics with header_style tag"() {
    given:
    def collector = CoreMetricCollector.getInstance()
    def baggageMetrics = BaggageMetrics.getInstance()

    when:
    baggageMetrics.onBaggageMalformed()
    collector.prepareMetrics()
    def metrics = collector.drain()

    then:
    def baggageMetric = metrics.find { it.metricName == "context_header_style.malformed" }
    baggageMetric != null
    baggageMetric.tags.contains("header_style:baggage")
    baggageMetric.value == 1
  }

  def "should collect baggage truncated metrics with byte count truncation_reason tag"() {
    given:
    def collector = CoreMetricCollector.getInstance()
    def baggageMetrics = BaggageMetrics.getInstance()

    when:
    baggageMetrics.onBaggageTruncatedByByteLimit()
    collector.prepareMetrics()
    def metrics = collector.drain()

    then:
    def baggageMetric = metrics.find {
      it.metricName == "context_header_style.truncated" &&
        it.tags.contains("truncation_reason:baggage_byte_count_exceeded")
    }
    baggageMetric != null
    baggageMetric.value == 1
  }

  def "should collect baggage truncated metrics with item count truncation_reason tag"() {
    given:
    def collector = CoreMetricCollector.getInstance()
    def baggageMetrics = BaggageMetrics.getInstance()

    when:
    baggageMetrics.onBaggageTruncatedByItemLimit()
    collector.prepareMetrics()
    def metrics = collector.drain()

    then:
    def baggageMetric = metrics.find {
      it.metricName == "context_header_style.truncated" &&
        it.tags.contains("truncation_reason:baggage_item_count_exceeded")
    }
    baggageMetric != null
    baggageMetric.value == 1
  }

  def "should not create baggage metrics when no events occurred"() {
    given:
    def collector = CoreMetricCollector.getInstance()

    when:
    collector.prepareMetrics()
    def metrics = collector.drain()

    then:
    def foundMetrics = metrics.findAll { it.metricName.startsWith("context_header_style.") }
    foundMetrics.isEmpty()
  }

  def "should reset baggage counters after prepareMetrics"() {
    given:
    def collector = CoreMetricCollector.getInstance()
    def baggageMetrics = BaggageMetrics.getInstance()

    when:
    baggageMetrics.onBaggageExtracted()
    baggageMetrics.onBaggageInjected()
    collector.prepareMetrics()
    collector.drain()
    collector.prepareMetrics()
    def metrics = collector.drain()

    then:
    def foundMetrics = metrics.findAll { it.metricName.startsWith("context_header_style.") }
    foundMetrics.isEmpty()
  }
}