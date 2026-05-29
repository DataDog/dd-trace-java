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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
            "jvm.system.cpu.load_1m",
            "jvm.gc.duration");

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
  void jvmThreadCountIsBucketedByDaemonAndState() {
    MetricCollector collector = new MetricCollector();
    OtelMetricRegistry.INSTANCE.collectMetrics(collector);

    List<DataPointEntry> threadPoints = collector.points.get("jvm.thread.count");
    assertNotNull(threadPoints, "jvm.thread.count should have data points");
    assertFalse(threadPoints.isEmpty(), "jvm.thread.count should have data points");

    // Every data point must carry both jvm.thread.daemon (Boolean) and jvm.thread.state (String).
    Set<String> validStates = new HashSet<>();
    for (Thread.State state : Thread.State.values()) {
      validStates.add(state.name().toLowerCase(Locale.ROOT));
    }
    long totalThreads = 0;
    for (DataPointEntry point : threadPoints) {
      Object daemon = point.attrs.get("jvm.thread.daemon");
      Object state = point.attrs.get("jvm.thread.state");
      assertNotNull(daemon, "jvm.thread.count point missing jvm.thread.daemon: " + point.attrs);
      assertNotNull(state, "jvm.thread.count point missing jvm.thread.state: " + point.attrs);
      assertTrue(
          "true".equals(daemon.toString()) || "false".equals(daemon.toString()),
          "jvm.thread.daemon must be a boolean string, got " + daemon);
      assertTrue(
          validStates.contains(state.toString()),
          "jvm.thread.state must be one of " + validStates + ", got " + state);
      assertTrue(
          point.value.longValue() > 0,
          "jvm.thread.count bucket should be positive (empty buckets must be skipped), got "
              + point.value
              + " for "
              + point.attrs);
      totalThreads += point.value.longValue();
    }
    assertTrue(totalThreads > 0, "Sum of jvm.thread.count buckets should be positive");

    // The test JVM has at minimum: the main test thread (non-daemon) plus GC/JMX/etc. daemon
    // threads — so we should observe at least one daemon=true and one daemon=false bucket.
    Set<String> daemonValues = collector.attributeValues("jvm.thread.count", "jvm.thread.daemon");
    assertTrue(
        daemonValues.contains("true") && daemonValues.contains("false"),
        "jvm.thread.count should emit both daemon and non-daemon buckets, got: " + daemonValues);
  }

  @Test
  void jvmMemoryInitHasHeapNonHeapAndPoolAttributes() {
    MetricCollector collector = new MetricCollector();
    OtelMetricRegistry.INSTANCE.collectMetrics(collector);

    Set<String> types = collector.attributeValues("jvm.memory.init", "jvm.memory.type");
    assertTrue(types.contains("heap"), "jvm.memory.init should have heap aggregate");
    assertTrue(types.contains("non_heap"), "jvm.memory.init should have non_heap aggregate");

    Set<String> poolNames = collector.attributeValues("jvm.memory.init", "jvm.memory.pool.name");
    assertFalse(
        poolNames.isEmpty(),
        "jvm.memory.init should have per-pool data points carrying jvm.memory.pool.name");
  }

  @Test
  void jvmMemoryInitHeapAggregateIsPositive() {
    MetricCollector collector = new MetricCollector();
    OtelMetricRegistry.INSTANCE.collectMetrics(collector);

    List<DataPointEntry> points = collector.points.get("jvm.memory.init");
    assertNotNull(points, "jvm.memory.init should have data points");
    DataPointEntry heapAggregate =
        points.stream()
            .filter(
                p ->
                    "heap".equals(p.attrs.get("jvm.memory.type"))
                        && p.attrs.get("jvm.memory.pool.name") == null)
            .findFirst()
            .orElse(null);
    assertNotNull(heapAggregate, "jvm.memory.init should have a heap aggregate data point");
    assertTrue(
        heapAggregate.value.longValue() > 0,
        "jvm.memory.init heap aggregate should be positive, got " + heapAggregate.value);
  }

  @Test
  void jvmGcDurationRecordsDataPointsAfterGc() throws InterruptedException {
    // Force a GC; the JMX NotificationListener should observe the event and record a data
    // point onto the jvm.gc.duration histogram.
    System.gc();

    // JMX delivers the notification on the JVM's internal notification thread, so we have
    // to poll briefly. Two seconds is generous — delivery is typically sub-50ms.
    List<DataPointEntry> points = null;
    long deadlineNanos = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
    while (System.nanoTime() < deadlineNanos) {
      MetricCollector collector = new MetricCollector();
      OtelMetricRegistry.INSTANCE.collectMetrics(collector);
      points = collector.points.get("jvm.gc.duration");
      if (points != null && !points.isEmpty()) {
        break;
      }
      Thread.sleep(50);
    }

    assertNotNull(points, "jvm.gc.duration should have data points after System.gc()");
    assertFalse(points.isEmpty(), "jvm.gc.duration should have at least one data point");
    assertTrue(
        points.stream()
            .allMatch(
                p -> p.attrs.containsKey("jvm.gc.name") && p.attrs.containsKey("jvm.gc.action")),
        "Every jvm.gc.duration data point should carry jvm.gc.name and jvm.gc.action attributes");
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
