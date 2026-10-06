package opentelemetry147.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.management.UnixOperatingSystemMXBean;
import datadog.trace.agent.jmxfetch.JvmOtlpRuntimeMetrics;
import datadog.trace.api.Config;
import datadog.trace.api.ProcessTags;
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
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

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
  void allDataPointsHaveLegacyJmxTags() {
    MetricCollector collector = new MetricCollector();
    OtelMetricRegistry.INSTANCE.collectMetrics(collector);
    String entrypointBasedir = expectedProcessTags().get("entrypoint.basedir");
    String entrypointName = processTagValue("entrypoint.name");
    String entrypointType = processTagValue("entrypoint.type");
    String entrypointWorkdir = processTagValue("entrypoint.workdir");

    int dataPointCount = 0;
    for (Map.Entry<String, List<DataPointEntry>> metric : collector.points.entrySet()) {
      for (DataPointEntry point : metric.getValue()) {
        dataPointCount++;
        assertEquals(
            "dd-java-agent default",
            point.attrs.get("instance"),
            metric.getKey() + " should carry the legacy instance tag");
        assertEquals(
            "jmxfetch-config",
            point.attrs.get("dd.internal.jmx_check_name"),
            metric.getKey() + " should carry the legacy JMX check name");
        assertEquals(
            Config.get().getRuntimeId(),
            point.attrs.get("runtime-id"),
            metric.getKey() + " should carry the tracer runtime ID");
        assertNotNull(
            point.attrs.get("jmx_domain"), metric.getKey() + " should carry the JMX domain");
        assertNotNull(point.attrs.get("type"), metric.getKey() + " should carry the MBean type");
        assertEquals(
            entrypointBasedir,
            point.attrs.get("entrypoint.basedir"),
            metric.getKey() + " should carry the process entrypoint base directory when present");
        assertEquals(
            entrypointName,
            point.attrs.get("entrypoint.name"),
            metric.getKey() + " should carry the process entrypoint name");
        assertEquals(
            entrypointType,
            point.attrs.get("entrypoint.type"),
            metric.getKey() + " should carry the process entrypoint type");
        assertEquals(
            entrypointWorkdir,
            point.attrs.get("entrypoint.workdir"),
            metric.getKey() + " should carry the process entrypoint workdir");
      }
    }
    assertTrue(dataPointCount > 0, "Expected at least one JVM runtime metric data point");
  }

  private static String processTagValue(String key) {
    String prefix = key + ":";
    List<String> processTags = ProcessTags.getTagsAsStringList();
    assertNotNull(processTags, "Process tags should be enabled for this test");
    return processTags.stream()
        .filter(tag -> tag.startsWith(prefix))
        .map(tag -> tag.substring(prefix.length()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing process tag " + key));
  }

  @Test
  void jvmMemoryMetricsCarryJmxFetchTagsOfMemoryAndMemoryPoolBeans() {
    MetricCollector collector = new MetricCollector();
    OtelMetricRegistry.INSTANCE.collectMetrics(collector);

    for (String metric :
        Arrays.asList(
            "jvm.memory.used",
            "jvm.memory.committed",
            "jvm.memory.limit",
            "jvm.memory.init",
            "jvm.memory.used_after_last_gc")) {
      List<DataPointEntry> points = collector.points.get(metric);
      assertNotNull(points, metric + " should have data points");
      assertFalse(points.isEmpty(), metric + " should have data points");
      for (DataPointEntry point : points) {
        assertMemoryJmxFetchTags(metric, point);
      }
    }
  }

  @TableTest({
    "Scenario               | Metric                     | Domain    | Type            | Name Attribute      ",
    "buffer memory used     | jvm.buffer.memory.used     | java.nio  | BufferPool      | jvm.buffer.pool.name",
    "buffer memory limit    | jvm.buffer.memory.limit    | java.nio  | BufferPool      | jvm.buffer.pool.name",
    "buffer count           | jvm.buffer.count           | java.nio  | BufferPool      | jvm.buffer.pool.name",
    "thread count           | jvm.thread.count           | java.lang | Threading       |                     ",
    "class loaded           | jvm.class.loaded           | java.lang | ClassLoading    |                     ",
    "class count            | jvm.class.count            | java.lang | ClassLoading    |                     ",
    "class unloaded         | jvm.class.unloaded         | java.lang | ClassLoading    |                     ",
    "cpu time               | jvm.cpu.time               | java.lang | OperatingSystem |                     ",
    "cpu count              | jvm.cpu.count              | java.lang | OperatingSystem |                     ",
    "cpu recent utilization | jvm.cpu.recent_utilization | java.lang | OperatingSystem |                     ",
    "system cpu utilization | jvm.system.cpu.utilization | java.lang | OperatingSystem |                     ",
    "system cpu load 1m     | jvm.system.cpu.load_1m     | java.lang | OperatingSystem |                     ",
    "file descriptor count  | jvm.file_descriptor.count  | java.lang | OperatingSystem |                     ",
    "file descriptor limit  | jvm.file_descriptor.limit  | java.lang | OperatingSystem |                     "
  })
  void jvmMetricsCarryJmxFetchTagsOfSourceBean(
      String metric, String domain, String type, String nameAttribute) {
    MetricCollector collector = new MetricCollector();
    OtelMetricRegistry.INSTANCE.collectMetrics(collector);

    List<DataPointEntry> points = collector.points.get(metric);
    assumeTrue(points != null && !points.isEmpty(), metric + " not reported on this JVM");
    for (DataPointEntry point : points) {
      String expectedName = nameAttribute == null ? null : (String) point.attrs.get(nameAttribute);
      if (nameAttribute != null) {
        assertNotNull(
            expectedName, metric + " point missing " + nameAttribute + ": " + point.attrs);
      }
      assertJmxFetchTags(metric, point, domain, type, expectedName);
    }
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
        "Every jvm.gc.duration point should carry jvm.gc.name and jvm.gc.action attributes");
    for (DataPointEntry point : points) {
      assertJmxFetchTags(
          "jvm.gc.duration",
          point,
          "java.lang",
          "GarbageCollector",
          (String) point.attrs.get("jvm.gc.name"));
    }
  }

  /** Collects the registry and runs {@code check} on every JVM runtime metric data point. */
  static void forEachJvmPoint(BiConsumer<String, DataPointEntry> check) {
    MetricCollector collector = new MetricCollector();
    OtelMetricRegistry.INSTANCE.collectMetrics(collector);
    List<DataPointEntry> memoryPoints = collector.points.get("jvm.memory.used");
    assertTrue(
        memoryPoints != null && !memoryPoints.isEmpty(), "jvm.memory.used should have data points");
    for (Map.Entry<String, List<DataPointEntry>> metric : collector.points.entrySet()) {
      if (metric.getKey().startsWith("jvm.")) {
        for (DataPointEntry point : metric.getValue()) {
          check.accept(metric.getKey(), point);
        }
      }
    }
  }

  static final List<String> ENTRYPOINT_TAGS =
      Arrays.asList(
          "entrypoint.basedir", "entrypoint.name", "entrypoint.type", "entrypoint.workdir");

  static Map<String, String> expectedProcessTags() {
    Map<String, String> result = new HashMap<>();
    List<String> processTags = ProcessTags.getTagsAsStringList();
    if (processTags != null) {
      for (String tag : processTags) {
        int separator = tag.indexOf(':');
        String key = tag.substring(0, separator);
        if (ENTRYPOINT_TAGS.contains(key)) {
          result.put(key, tag.substring(separator + 1));
        }
      }
    }
    return result;
  }

  static void assertJmxFetchTags(
      String metric, DataPointEntry point, String domain, String type, String name) {
    String context = metric + " " + point.attrs;
    assertEquals("dd-java-agent default", point.attrs.get("instance"), context);
    assertEquals("jmxfetch-config", point.attrs.get("dd.internal.jmx_check_name"), context);
    assertEquals(domain, point.attrs.get("jmx_domain"), context);
    assertEquals(type, point.attrs.get("type"), context);
    assertEquals(name, point.attrs.get("name"), context);
    String runtimeId = Config.get().getRuntimeId();
    assertEquals(runtimeId.isEmpty() ? null : runtimeId, point.attrs.get("runtime-id"), context);
    assertEquals(
        Config.get().getMergedJmxTags().get("_dd.injection.mode"),
        point.attrs.get("_dd.injection.mode"),
        context);
    Map<String, String> processTags = expectedProcessTags();
    for (String key : ENTRYPOINT_TAGS) {
      assertEquals(processTags.get(key), point.attrs.get(key), context);
    }
  }

  static void assertMemoryJmxFetchTags(String metric, DataPointEntry point) {
    String pool = (String) point.attrs.get("jvm.memory.pool.name");
    assertJmxFetchTags(metric, point, "java.lang", pool == null ? "Memory" : "MemoryPool", pool);
  }

  static void assertJavaLangJmxFetchTags(MetricCollector collector, String metric, String type) {
    List<DataPointEntry> points = collector.points.get(metric);
    assertNotNull(points, metric + " should have data points");
    for (DataPointEntry point : points) {
      assertJmxFetchTags(metric, point, "java.lang", type, null);
    }
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
