package opentelemetry147.metrics;

import static opentelemetry147.metrics.JvmOtlpRuntimeMetricsTest.forEachJvmPoint;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.agent.jmxfetch.JvmOtlpRuntimeMetrics;
import datadog.trace.api.ProcessTags;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

// Forked because ProcessTags and JvmOtlpRuntimeMetrics cache process-wide state.
class JvmOtlpRuntimeMetricsEntrypointBasedirForkedTest {

  @BeforeAll
  static void setUp() {
    System.setProperty("dd.metrics.otel.enabled", "true");
    ProcessTags.addTag("entrypoint.basedir", "test-basedir");
    JvmOtlpRuntimeMetrics.start(true);
  }

  @Test
  void allDataPointsHaveEntrypointBasedir() {
    forEachJvmPoint(
        (metric, point) ->
            assertEquals("test-basedir", point.attrs.get("entrypoint.basedir"), metric));
  }
}
