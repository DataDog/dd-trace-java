package opentelemetry147.metrics;

import static opentelemetry147.metrics.JvmOtlpRuntimeMetricsTest.forEachJvmPoint;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.agent.jmxfetch.JvmOtlpRuntimeMetrics;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

// Forked because Config and JvmOtlpRuntimeMetrics cache process-wide state.
class JvmOtlpRuntimeMetricsInjectionModeForkedTest {

  @BeforeAll
  static void setUp() {
    System.setProperty("dd.metrics.otel.enabled", "true");
    System.setProperty("dd.tags", "_dd.injection.mode:k8s,team:apm");
    JvmOtlpRuntimeMetrics.start(true);
  }

  @Test
  void onlyInjectionModeIsCopiedFromGlobalTags() {
    forEachJvmPoint(
        (metric, point) -> {
          String context = metric + " " + point.attrs;
          assertEquals("k8s", point.attrs.get("_dd.injection.mode"), context);
          assertNull(point.attrs.get("team"), context);
        });
  }
}
