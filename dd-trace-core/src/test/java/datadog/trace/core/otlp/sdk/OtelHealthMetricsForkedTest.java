package datadog.trace.core.otlp.sdk;

import static datadog.trace.agent.test.utils.OtelMetricCollector.collect;
import static datadog.trace.api.TracePropagationStyle.DATADOG;
import static datadog.trace.api.config.OtlpConfig.METRICS_OTEL_ENABLED;
import static datadog.trace.api.config.OtlpConfig.OTLP_METRICS_TEMPORALITY_PREFERENCE;
import static datadog.trace.api.config.OtlpConfig.TRACE_OTEL_SDK_SELF_MONITORING_METRICS_ENABLED;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.utils.OtelMetricCollector;
import datadog.trace.agent.test.utils.OtelMetricCollector.DataPointEntry;
import datadog.trace.api.Config;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.DDSpan;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.core.propagation.ExtractedContext;
import datadog.trace.core.propagation.PropagationTags;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

// Gate on: the instruments are registered once per JVM, so these tests need their own JVM.
// Cumulative temporality keeps the asserted values independent of any background collection.
@WithConfig(key = METRICS_OTEL_ENABLED, value = "true")
@WithConfig(key = TRACE_OTEL_SDK_SELF_MONITORING_METRICS_ENABLED, value = "true")
@WithConfig(key = OTLP_METRICS_TEMPORALITY_PREFERENCE, value = "cumulative")
class OtelHealthMetricsForkedTest extends DDCoreJavaSpecification {
  @Test
  void gateOnReportsEveryOriginAndTracksSpanLifecycle() {
    HealthMetrics metrics = OtelSdkSelfMonitoring.healthMetrics(Config.get());
    assertSame(OtelHealthMetrics.INSTANCE, metrics);

    // the tracer would otherwise record on the same sink; this test drives the callbacks itself
    CoreTracer tracer =
        tracerBuilder().writer(new ListWriter()).healthMetrics(HealthMetrics.NO_OP).build();
    try {
      // every origin is reported from the first collection, as 0 until a span starts
      OtelMetricCollector before = collect();
      assertEquals(byOrigin(0, 0, 0), valuesByOrigin(before, "otel.sdk.span.started"));
      assertEquals(0L, liveValue(before));

      DDSpan root = (DDSpan) tracer.buildSpan("test", "root").start();
      DDSpan child = (DDSpan) tracer.buildSpan("test", "child").asChildOf(root).start();
      DDSpan server =
          (DDSpan) tracer.buildSpan("test", "server").asChildOf(remoteParentContext()).start();
      metrics.onCreateSpan(root);
      metrics.onCreateSpan(child);
      metrics.onCreateSpan(server);
      metrics.onEndSpan();
      OtelMetricCollector collector = collect();

      assertEquals(byOrigin(1, 1, 1), valuesByOrigin(collector, "otel.sdk.span.started"));
      assertEquals(2L, liveValue(collector));

      // once every span ends, live reports 0
      metrics.onEndSpan();
      metrics.onEndSpan();
      assertEquals(0L, liveValue(collect()));

      server.finish();
      child.finish();
      root.finish();
    } finally {
      tracer.close();
    }
  }

  private static ExtractedContext remoteParentContext() {
    return new ExtractedContext(
        DDTraceId.ONE,
        2,
        PrioritySampling.SAMPLER_KEEP,
        null,
        0,
        emptyMap(),
        emptyMap(),
        null,
        PropagationTags.factory().empty(),
        null,
        DATADOG);
  }

  private static Map<String, Long> byOrigin(long none, long local, long remote) {
    Map<String, Long> expected = new HashMap<>();
    expected.put("none", none);
    expected.put("local", local);
    expected.put("remote", remote);
    return expected;
  }

  /** The single {@code otel.sdk.span.live} value; the metric carries no attributes. */
  private static long liveValue(OtelMetricCollector collector) {
    List<DataPointEntry> points = collector.points.getOrDefault("otel.sdk.span.live", emptyList());
    assertEquals(1, points.size());
    assertTrue(points.get(0).attrs.isEmpty());
    return points.get(0).value.longValue();
  }

  private static Map<String, Long> valuesByOrigin(OtelMetricCollector collector, String metric) {
    Map<String, Long> values = new HashMap<>();
    for (DataPointEntry point : collector.points.getOrDefault(metric, emptyList())) {
      values.put((String) point.attrs.get("otel.span.parent.origin"), point.value.longValue());
    }
    return values;
  }
}
