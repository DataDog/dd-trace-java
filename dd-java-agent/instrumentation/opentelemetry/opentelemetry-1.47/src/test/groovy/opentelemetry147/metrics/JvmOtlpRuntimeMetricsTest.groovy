package opentelemetry147.metrics

import datadog.opentelemetry.shim.metrics.JvmOtlpRuntimeMetrics
import datadog.opentelemetry.shim.metrics.OtelMeterProvider
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.otel.common.OtelInstrumentationScope
import datadog.trace.bootstrap.otel.metrics.OtelInstrumentDescriptor
import datadog.trace.bootstrap.otel.metrics.data.OtelMetricRegistry
import datadog.trace.bootstrap.otlp.metrics.OtlpDataPoint
import datadog.trace.bootstrap.otlp.metrics.OtlpDoublePoint
import datadog.trace.bootstrap.otlp.metrics.OtlpLongPoint
import datadog.trace.bootstrap.otlp.metrics.OtlpMetricVisitor
import datadog.trace.bootstrap.otlp.metrics.OtlpMetricsVisitor
import datadog.trace.bootstrap.otlp.metrics.OtlpScopedMetricsVisitor

/**
 * Tests that JVM runtime metrics are registered and exported via OTLP
 * using OTel semantic convention names (jvm.memory.used, jvm.thread.count, etc.).
 *
 * Ref: https://opentelemetry.io/docs/specs/semconv/runtime/jvm-metrics/
 * Ref: https://github.com/DataDog/semantic-core/blob/main/sor/domains/metrics/integrations/java/_equivalence/
 */
class JvmOtlpRuntimeMetricsTest extends InstrumentationSpecification {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.metrics.otel.enabled", "true")
  }

  def "JVM runtime metrics are registered and produce data points"() {
    when:
    JvmOtlpRuntimeMetrics.start()
    def collector = new MetricCollector()
    OtelMetricRegistry.INSTANCE.collectMetrics(collector)

    then:
    // OtelInstrumentDescriptor.name is UTF8BytesString, convert to String for comparison
    def names = collector.metricNames.collect { it.toString() }
    // Memory (5 metrics, all UpDownCounter per spec)
    "jvm.memory.used" in names
    "jvm.memory.committed" in names
    "jvm.memory.limit" in names
    "jvm.memory.init" in names
    "jvm.memory.used_after_last_gc" in names
    // Buffers (3 metrics, UpDownCounter per spec)
    "jvm.buffer.memory.used" in names
    "jvm.buffer.memory.limit" in names
    "jvm.buffer.count" in names
    // Threads (1 metric, UpDownCounter per spec)
    "jvm.thread.count" in names
    // Classes (3 metrics: loaded/unloaded are Counter, count is UpDownCounter per spec)
    "jvm.class.loaded" in names
    "jvm.class.count" in names
    "jvm.class.unloaded" in names
    // CPU (4 metrics per spec)
    "jvm.cpu.time" in names
    "jvm.cpu.count" in names
    "jvm.cpu.recent_utilization" in names
    "jvm.system.cpu.utilization" in names
    // NOT included: jvm.gc.duration (spec requires Histogram, JMX can't produce it)
    // NOT included: jvm.gc.count (not in OTel spec)
  }

  def "jvm.memory.used has heap and non_heap type attributes"() {
    when:
    JvmOtlpRuntimeMetrics.start()
    def collector = new MetricCollector()
    OtelMetricRegistry.INSTANCE.collectMetrics(collector)

    then:
    def types = collector.attributeValues("jvm.memory.used", "jvm.memory.type")
    types.contains("heap")
    types.contains("non_heap")
  }

  def "jvm.memory.used heap value is positive"() {
    when:
    JvmOtlpRuntimeMetrics.start()
    def collector = new MetricCollector()
    OtelMetricRegistry.INSTANCE.collectMetrics(collector)

    then:
    def heapPoints = collector.points["jvm.memory.used"]
      .findAll { it.attrs["jvm.memory.type"] == "heap" }
    heapPoints.size() > 0
    heapPoints[0].value > 0
  }

  def "jvm.thread.count is positive"() {
    when:
    JvmOtlpRuntimeMetrics.start()
    def collector = new MetricCollector()
    OtelMetricRegistry.INSTANCE.collectMetrics(collector)

    then:
    def threadPoints = collector.points["jvm.thread.count"]
    threadPoints.size() > 0
    threadPoints[0].value > 0
  }

  static class DataPointEntry {
    Map<String, Object> attrs
    Number value
  }

  static class MetricCollector implements OtlpMetricsVisitor, OtlpScopedMetricsVisitor, OtlpMetricVisitor {
    String currentInstrument = ""
    Map<String, Object> currentAttrs = [:]
    Set<String> metricNames = new LinkedHashSet<>()
    Map<String, List<DataPointEntry>> points = [:].withDefault { [] }

    @Override
    OtlpScopedMetricsVisitor visitScopedMetrics(OtelInstrumentationScope scope) {
      return this
    }

    @Override
    OtlpMetricVisitor visitMetric(OtelInstrumentDescriptor descriptor) {
      currentInstrument = descriptor.name.toString()
      metricNames.add(descriptor.name.toString())
      return this
    }

    @Override
    void visitAttribute(int type, String key, Object value) {
      currentAttrs.put(key.toString(), value.toString())
    }

    @Override
    void visitDataPoint(OtlpDataPoint point) {
      def attrs = new HashMap(currentAttrs)
      currentAttrs.clear()
      Number value = 0
      if (point instanceof OtlpLongPoint) {
        value = ((OtlpLongPoint) point).value
      } else if (point instanceof OtlpDoublePoint) {
        value = ((OtlpDoublePoint) point).value
      }
      def entry = new DataPointEntry()
      entry.attrs = attrs
      entry.value = value
      points[currentInstrument].add(entry)
    }

    Set<String> attributeValues(String metricName, String attrKey) {
      points[metricName]
        .collect { it.attrs[attrKey] }
        .findAll { it != null }
        .toSet()
    }
  }
}
