package opentelemetry147.metrics;

import static opentelemetry147.metrics.JvmOtlpRuntimeMetricsTest.ENTRYPOINT_TAGS;
import static opentelemetry147.metrics.JvmOtlpRuntimeMetricsTest.assertJavaLangJmxFetchTags;
import static opentelemetry147.metrics.JvmOtlpRuntimeMetricsTest.forEachJvmPoint;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.agent.jmxfetch.JvmOtlpRuntimeMetrics;
import datadog.trace.bootstrap.otel.metrics.data.OtelMetricRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

// Forked because Config, ProcessTags, and JvmOtlpRuntimeMetrics cache process-wide state.
class JvmOtlpRuntimeMetricsNoProcessTagsForkedTest {

  @BeforeAll
  static void setUp() {
    System.setProperty("dd.metrics.otel.enabled", "true");
    System.setProperty("dd.experimental.propagate.process.tags.enabled", "false");
    JvmOtlpRuntimeMetrics.start(true);
  }

  @Test
  void entrypointTagsAreOmittedWhenProcessTagsAreDisabled() {
    forEachJvmPoint(
        (metric, point) -> {
          for (String key : ENTRYPOINT_TAGS) {
            assertNull(point.attrs.get(key), metric + " " + point.attrs);
          }
        });

    JvmOtlpRuntimeMetricsTest.MetricCollector collector =
        new JvmOtlpRuntimeMetricsTest.MetricCollector();
    OtelMetricRegistry.INSTANCE.collectMetrics(collector);
    assertJavaLangJmxFetchTags(collector, "jvm.thread.count", "Threading");
  }
}
