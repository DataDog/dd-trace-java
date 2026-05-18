package opentelemetry147.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.UnixOperatingSystemMXBean;
import datadog.trace.agent.jmxfetch.JvmOtlpRuntimeMetrics;
import datadog.trace.bootstrap.otel.common.OtelInstrumentationScope;
import datadog.trace.bootstrap.otel.metrics.OtelInstrumentDescriptor;
import datadog.trace.bootstrap.otel.metrics.data.OtelMetricRegistry;
import datadog.trace.bootstrap.otlp.metrics.OtlpDataPoint;
import datadog.trace.bootstrap.otlp.metrics.OtlpDoublePoint;
import datadog.trace.bootstrap.otlp.metrics.OtlpLongPoint;
import datadog.trace.bootstrap.otlp.metrics.OtlpMetricVisitor;
import datadog.trace.bootstrap.otlp.metrics.OtlpMetricsVisitor;
import datadog.trace.bootstrap.otlp.metrics.OtlpScopedMetricsVisitor;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests that JVM runtime metrics are registered and exported via OTLP using OTel semantic
 * convention names (jvm.memory.used, jvm.thread.count, etc.).
 *
 * <p>Ref: https://opentelemetry.io/docs/specs/semconv/runtime/jvm-metrics/
 *
 * <p>Ref:
 * https://github.com/DataDog/semantic-core/blob/main/sor/domains/metrics/integrations/java/_equivalence/
 */
public class JvmOtlpRuntimeMetricsTest {

  @BeforeAll
  static void setUp() {
    System.setProperty("dd.metrics.otel.enabled", "true");
    JvmOtlpRuntimeMetrics.start(true);
  }

  @Test
  void registersExpectedJvmMetrics() {
    MetricCollector collector = new MetricCollector();
    OtelMetricRegistry.INSTANCE.collectMetrics(collector);

    List<String> expectedMetrics =
        Arrays.asList(
            "jvm.memory.used",
            "jvm.memory.committed",
            "jvm.memory.limit",
            "jvm.memory.init",
            "jvm.memory.used_after_last_gc",
            "jvm.buffer.memory.used",
            "jvm.buffer.memory.limit",
            "jvm.buffer.count",
            "jvm.thread.count",
            "jvm.class.loaded",
            "jvm.class.count",
            "jvm.class.unloaded",
            "jvm.cpu.time",
            "jvm.cpu.count",
            "jvm.cpu.recent_utilization",
            "jvm.system.cpu.utilization",
            "jvm.system.cpu.load_1m");

    Set<String> names = collector.metricNames;
    for (String metric : expectedMetrics) {
      assertTrue(
          names.contains(metric),
          "Expected metric '" + metric + "' not found. Got: " + new TreeSet<>(names));
    }

    int expectedSize = expectedMetrics.size();
    if (ManagementFactory.getOperatingSystemMXBean() instanceof UnixOperatingSystemMXBean) {
      assertTrue(
          names.contains("jvm.file_descriptor.count"),
          "Expected jvm.file_descriptor.count on Unix. Got: " + new TreeSet<>(names));
      assertTrue(
          names.contains("jvm.file_descriptor.limit"),
          "Expected jvm.file_descriptor.limit on Unix. Got: " + new TreeSet<>(names));
      expectedSize += 2;
    }

    assertEquals(expectedSize, names.size(), "Unexpected metric count: " + new TreeSet<>(names));

    // No DD-proprietary names should be present
    List<String> ddNames =
        names.stream()
            .filter(n -> n.startsWith("jvm.heap_memory") || n.startsWith("jvm.thread_count"))
            .collect(Collectors.toList());
    assertTrue(ddNames.isEmpty(), "DD-proprietary names leaked: " + ddNames);
  }

  @Test
  void jvmMemoryUsedHasHeapAndNonHeapTypeAttributes() {
    MetricCollector collector = new MetricCollector();
    OtelMetricRegistry.INSTANCE.collectMetrics(collector);

    Set<String> types = collector.attributeValues("jvm.memory.used", "jvm.memory.type");
    assertTrue(types.contains("heap"), "jvm.memory.used should have heap attribute");
    assertTrue(types.contains("non_heap"), "jvm.memory.used should have non_heap attribute");
  }

  @Test
  void jvmMemoryUsedHeapValueIsPositive() {
    MetricCollector collector = new MetricCollector();
    OtelMetricRegistry.INSTANCE.collectMetrics(collector);

    List<DataPointEntry> points = collector.points.get("jvm.memory.used");
    assertNotNull(points, "jvm.memory.used should have data points");
    DataPointEntry heapAggregate =
        points.stream()
            .filter(
                p ->
                    "heap".equals(p.attrs.get("jvm.memory.type"))
                        && p.attrs.get("jvm.memory.pool.name") == null)
            .findFirst()
            .orElse(null);
    assertNotNull(heapAggregate, "jvm.memory.used should have a heap aggregate data point");
    assertTrue(
        heapAggregate.value.longValue() > 0,
        "jvm.memory.used heap aggregate should be positive, got " + heapAggregate.value);
  }

  @Test
  void jvmThreadCountIsPositive() {
    MetricCollector collector = new MetricCollector();
    OtelMetricRegistry.INSTANCE.collectMetrics(collector);

    List<DataPointEntry> threadPoints = collector.points.get("jvm.thread.count");
    assertNotNull(threadPoints, "jvm.thread.count should have data points");
    assertFalse(threadPoints.isEmpty(), "jvm.thread.count should have data points");
    assertTrue(
        threadPoints.get(0).value.longValue() > 0,
        "jvm.thread.count value should be positive, got " + threadPoints.get(0).value);
  }

  @Test
  void startIsIdempotent() {
    MetricCollector before = new MetricCollector();
    OtelMetricRegistry.INSTANCE.collectMetrics(before);
    int countBefore = before.metricNames.size();

    JvmOtlpRuntimeMetrics.start(true);
    JvmOtlpRuntimeMetrics.start(true);

    MetricCollector after = new MetricCollector();
    OtelMetricRegistry.INSTANCE.collectMetrics(after);
    assertEquals(
        countBefore,
        after.metricNames.size(),
        "Repeated start() must not register duplicate instruments");
  }

  static final class DataPointEntry {
    final Map<String, Object> attrs;
    final Number value;

    DataPointEntry(Map<String, Object> attrs, Number value) {
      this.attrs = attrs;
      this.value = value;
    }
  }

  static final class MetricCollector
      implements OtlpMetricsVisitor, OtlpScopedMetricsVisitor, OtlpMetricVisitor {

    String currentInstrument = "";
    final Map<String, Object> currentAttrs = new LinkedHashMap<>();
    final Set<String> metricNames = new LinkedHashSet<>();
    final Map<String, List<DataPointEntry>> points = new LinkedHashMap<>();

    @Override
    public OtlpScopedMetricsVisitor visitScopedMetrics(OtelInstrumentationScope scope) {
      return this;
    }

    @Override
    public OtlpMetricVisitor visitMetric(OtelInstrumentDescriptor descriptor) {
      currentInstrument = descriptor.getName().toString();
      metricNames.add(currentInstrument);
      points.computeIfAbsent(currentInstrument, k -> new ArrayList<>());
      return this;
    }

    @Override
    public void visitAttribute(int type, String key, Object value) {
      currentAttrs.put(key, value == null ? null : value.toString());
    }

    @Override
    public void visitDataPoint(OtlpDataPoint point) {
      Map<String, Object> attrs = new HashMap<>(currentAttrs);
      currentAttrs.clear();
      Number value = 0;
      if (point instanceof OtlpLongPoint) {
        value = ((OtlpLongPoint) point).value;
      } else if (point instanceof OtlpDoublePoint) {
        value = ((OtlpDoublePoint) point).value;
      }
      points
          .computeIfAbsent(currentInstrument, k -> new ArrayList<>())
          .add(new DataPointEntry(attrs, value));
    }

    Set<String> attributeValues(String metricName, String attrKey) {
      List<DataPointEntry> entries = points.get(metricName);
      if (entries == null) {
        return new LinkedHashSet<>();
      }
      return entries.stream()
          .map(e -> e.attrs.get(attrKey))
          .filter(Objects::nonNull)
          .map(Object::toString)
          .collect(Collectors.toCollection(LinkedHashSet::new));
    }
  }
}
