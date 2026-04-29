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

  def "registers exactly 18 OTel-named JVM runtime metrics"() {
    when:
    JvmOtlpRuntimeMetrics.start()
    def collector = new MetricCollector()
    OtelMetricRegistry.INSTANCE.collectMetrics(collector)

    then:
    def names = collector.metricNames.collect { it.toString() }

    def expectedMetrics = [
      // Memory (5 metrics)
      "jvm.memory.used",
      "jvm.memory.committed",
      "jvm.memory.limit",
      "jvm.memory.init",
      "jvm.memory.used_after_last_gc",
      // Buffers (3 metrics)
      "jvm.buffer.memory.used",
      "jvm.buffer.memory.limit",
      "jvm.buffer.count",
      // Threads (1 metric)
      "jvm.thread.count",
      // Classes (3 metrics)
      "jvm.class.loaded",
      "jvm.class.count",
      "jvm.class.unloaded",
      // CPU (4 metrics)
      "jvm.cpu.time",
      "jvm.cpu.count",
      "jvm.cpu.recent_utilization",
      "jvm.system.cpu.utilization",
      // File descriptors (2 metrics)
      "jvm.file_descriptor.count",
      "jvm.file_descriptor.limit",
    ]

    for (metric in expectedMetrics) {
      assert metric in names : "Expected metric '${metric}' not found. Got: ${names.sort()}"
    }

    names.size() == 18

    // No DD-proprietary names should be present
    def ddNames = names.findAll { it.startsWith("jvm.heap_memory") || it.startsWith("jvm.thread_count") }
    ddNames.isEmpty()
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
