package opentelemetry147.metrics;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.jmxfetch.JvmOtlpRuntimeMetrics;
import datadog.trace.bootstrap.otel.metrics.data.OtelMetricRegistry;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

// Forked test: runs in an isolated JVM and starts JvmOtlpRuntimeMetrics with the experimental
// flag OFF, verifying that Development-status instruments are not registered and that the
// jvm.gc.cause attribute is omitted from jvm.gc.duration data points. The JvmOtlpRuntimeMetrics
// class uses a one-shot AtomicBoolean to guard registration, so this scenario must run in its
// own JVM separate from the always-on JvmOtlpRuntimeMetricsTest.
class JvmOtlpRuntimeMetricsForkedTest {

  @BeforeAll
  static void setUp() {
    System.setProperty("dd.metrics.otel.enabled", "true");
    JvmOtlpRuntimeMetrics.start(false);
  }

  @Test
  void registersOnlyStableMetricsWhenExperimentalDisabled() {
    JvmOtlpRuntimeMetricsTest.MetricCollector collector =
        new JvmOtlpRuntimeMetricsTest.MetricCollector();
    OtelMetricRegistry.INSTANCE.collectMetrics(collector);

    Set<String> names = collector.metricNames;

    List<String> expectedStableMetrics =
        Arrays.asList(
            "jvm.memory.used",
            "jvm.memory.committed",
            "jvm.memory.limit",
            "jvm.memory.used_after_last_gc",
            "jvm.thread.count",
            "jvm.class.loaded",
            "jvm.class.count",
            "jvm.class.unloaded",
            "jvm.cpu.time",
            "jvm.cpu.count",
            "jvm.cpu.recent_utilization",
            "jvm.gc.duration");
    for (String metric : expectedStableMetrics) {
      assertTrue(
          names.contains(metric),
          "Expected stable metric '" + metric + "' not found. Got: " + new TreeSet<>(names));
    }

    List<String> developmentMetrics =
        Arrays.asList(
            "jvm.memory.init",
            "jvm.buffer.memory.used",
            "jvm.buffer.memory.limit",
            "jvm.buffer.count",
            "jvm.system.cpu.utilization",
            "jvm.system.cpu.load_1m",
            "jvm.file_descriptor.count",
            "jvm.file_descriptor.limit");
    for (String metric : developmentMetrics) {
      assertFalse(
          names.contains(metric),
          "Development metric '"
              + metric
              + "' should not be registered when experimental disabled. Got: "
              + new TreeSet<>(names));
    }
  }

  @Test
  void jvmGcDurationDataPointsOmitGcCauseWhenExperimentalDisabled() throws InterruptedException {
    System.gc();

    List<JvmOtlpRuntimeMetricsTest.DataPointEntry> points = null;
    long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (System.nanoTime() < deadlineNanos) {
      JvmOtlpRuntimeMetricsTest.MetricCollector collector =
          new JvmOtlpRuntimeMetricsTest.MetricCollector();
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
                p ->
                    p.attrs.containsKey("jvm.gc.name")
                        && p.attrs.containsKey("jvm.gc.action")
                        && !p.attrs.containsKey("jvm.gc.cause")),
        "jvm.gc.duration data points must carry jvm.gc.name and jvm.gc.action, but not jvm.gc.cause"
            + " when experimental disabled");
  }
}
