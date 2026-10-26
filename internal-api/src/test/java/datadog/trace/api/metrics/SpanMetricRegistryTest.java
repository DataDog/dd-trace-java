package datadog.trace.api.metrics;

import static datadog.trace.api.config.GeneralConfig.TELEMETRY_ENABLED;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectSysConfig;
import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.test.util.DDJavaSpecification;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class SpanMetricRegistryTest extends DDJavaSpecification {

  @TableTest({
    "scenario           | telemetryEnabled",
    "telemetry enabled  | true            ",
    "telemetry disabled | false           "
  })
  void testRegistryNotCrashIfTelemetryIsDisabled(String telemetryEnabled) {
    injectSysConfig(TELEMETRY_ENABLED, telemetryEnabled);

    SpanMetricRegistry spanMetricRegistry = SpanMetricRegistry.getInstance();
    try {
      SpanMetrics spanMetrics = spanMetricRegistry.get("test-api");
      assertDoesNotThrow(
          () -> {
            spanMetrics.onSpanCreated();
            spanMetrics.onSpanFinished();
          });
    } finally {
      if (spanMetricRegistry instanceof SpanMetricRegistryImpl) {
        for (SpanMetricsImpl spanMetrics :
            ((SpanMetricRegistryImpl) spanMetricRegistry).getSpanMetrics()) {
          for (CoreCounter counter : spanMetrics.getCounters()) {
            counter.getValueAndReset();
          }
        }
      }
    }
  }

  @Test
  void testRegistryApi() {
    SpanMetricRegistryImpl spanMetricRegistry = SpanMetricRegistryImpl.create();

    // when
    SpanMetrics metrics1 = spanMetricRegistry.get("test1");
    SpanMetrics metrics1Ref = spanMetricRegistry.get("test1");

    // then
    assertSame(metrics1, metrics1Ref);

    // when
    SpanMetrics metrics2 = spanMetricRegistry.get("test2");
    Collection<SpanMetricsImpl> metricsCollection = spanMetricRegistry.getSpanMetrics();

    // then
    assertEquals(2, metricsCollection.size());
    assertEquals(new HashSet<>(asList(metrics1, metrics2)), new HashSet<>(metricsCollection));

    // when
    metrics1.onSpanCreated();
    metrics1.onSpanFinished();
    metrics1.onSpanCreated();
    metrics2.onSpanCreated();

    // then
    assertEquals(2, counter(metrics1, 0).getValueAndReset());
    assertEquals(1, counter(metrics1, 1).getValueAndReset());
    assertEquals(1, counter(metrics2, 0).getValueAndReset());
    assertEquals(0, counter(metrics2, 1).getValueAndReset());

    // when
    metrics1.onSpanFinished();
    metrics2.onSpanCreated();
    metrics2.onSpanCreated();
    metrics2.onSpanCreated();
    metrics2.onSpanCreated();
    metrics2.onSpanCreated();

    // then
    assertEquals(0, counter(metrics1, 0).getValueAndReset());
    assertEquals(1, counter(metrics1, 1).getValueAndReset());
    assertEquals(5, counter(metrics2, 0).getValueAndReset());
    assertEquals(0, counter(metrics2, 1).getValueAndReset());

    // and
    String summary1 = "test1: spans_created=2, spans_finished=2\n";
    String summary2 = "test2: spans_created=6, spans_finished=0\n";
    String summary = spanMetricRegistry.summary();
    assertTrue(
        summary.equals(summary1 + summary2) || summary.equals(summary2 + summary1),
        "Unexpected summary: " + summary);
  }

  private static CoreCounter counter(SpanMetrics metrics, int index) {
    return new ArrayList<>(((SpanMetricsImpl) metrics).getCounters()).get(index);
  }
}
