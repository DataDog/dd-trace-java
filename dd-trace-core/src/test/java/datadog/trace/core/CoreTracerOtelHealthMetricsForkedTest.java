package datadog.trace.core;

import static datadog.trace.agent.test.utils.OtelMetricCollector.collect;
import static datadog.trace.api.TracePropagationStyle.DATADOG;
import static datadog.trace.api.config.GeneralConfig.HEALTH_METRICS_ENABLED;
import static datadog.trace.api.config.OtlpConfig.METRICS_OTEL_ENABLED;
import static datadog.trace.api.config.OtlpConfig.OTLP_METRICS_TEMPORALITY_PREFERENCE;
import static datadog.trace.api.config.OtlpConfig.TRACE_OTEL_SDK_SELF_MONITORING_METRICS_ENABLED;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.agent.test.utils.OtelMetricCollector;
import datadog.trace.agent.test.utils.OtelMetricCollector.DataPointEntry;
import datadog.trace.api.Config;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.monitor.CompositeHealthMetrics;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.core.otlp.sdk.OtelSdkSelfMonitoring;
import datadog.trace.core.propagation.ExtractedContext;
import datadog.trace.core.propagation.PropagationTags;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

// Gate on: the instruments are registered once per JVM, so these tests need their own JVM. The
// counters are shared by every test in it, so each test asserts the change it causes.
// Cumulative temporality and paused remote communication (no OTLP metrics service) keep the
// asserted values independent of background collection.
// DD health metrics stay on by default, so unless a test turns them off the tracer uses both sinks.
@WithConfig(key = METRICS_OTEL_ENABLED, value = "true")
@WithConfig(key = TRACE_OTEL_SDK_SELF_MONITORING_METRICS_ENABLED, value = "true")
@WithConfig(key = OTLP_METRICS_TEMPORALITY_PREFERENCE, value = "cumulative")
class CoreTracerOtelHealthMetricsForkedTest extends DDCoreJavaSpecification {
  @Test
  @WithConfig(key = HEALTH_METRICS_ENABLED, value = "false")
  void usesOnlyTheOtelSinkWhenDatadogHealthMetricsAreDisabled() {
    HealthMetrics otel = OtelSdkSelfMonitoring.healthMetrics(Config.get());
    assertNotNull(otel);
    CoreTracer tracer = newTracer();
    try {
      assertSame(otel, tracer.healthMetrics);
    } finally {
      tracer.close();
    }
  }

  @Test
  void combinesBothSinksAndCountsEverySpanCreated() {
    CoreTracer tracer = newTracer();
    try {
      assertInstanceOf(CompositeHealthMetrics.class, tracer.healthMetrics);
      Snapshot before = Snapshot.take();

      DDSpan root = (DDSpan) tracer.buildSpan("test", "root").start();
      DDSpan child = (DDSpan) tracer.buildSpan("test", "child").asChildOf(root).start();
      child.finish();
      root.finish();

      Snapshot after = Snapshot.take();
      assertEquals(2L, after.totalStartedSince(before));
      assertEquals(0L, after.liveSince(before));
    } finally {
      tracer.close();
    }
  }

  @Test
  void countsSpansByParentOriginAndTracksLiveSpans() {
    CoreTracer tracer = newTracer();
    try {
      Snapshot before = Snapshot.take();

      DDSpan root = (DDSpan) tracer.buildSpan("test", "root").start();
      DDSpan child = (DDSpan) tracer.buildSpan("test", "child").asChildOf(root).start();
      DDSpan server =
          (DDSpan)
              tracer
                  .buildSpan("test", "server")
                  .asChildOf(remoteParent(PrioritySampling.SAMPLER_KEEP))
                  .start();
      child.finish();

      Snapshot running = Snapshot.take();
      assertEquals(byOrigin(1, 1, 1), running.startedSince(before));
      assertEquals(2L, running.liveSince(before));

      // finishing twice does not count twice
      root.finish();
      server.finish();
      server.finish();

      Snapshot after = Snapshot.take();
      assertEquals(byOrigin(1, 1, 1), after.startedSince(before));
      assertEquals(0L, after.liveSince(before));
    } finally {
      tracer.close();
    }
  }

  @Test
  void phasedFinishedSpanLeavesLiveBeforeItIsPublished() {
    CoreTracer tracer = newTracer();
    try {
      Snapshot before = Snapshot.take();

      DDSpan span = (DDSpan) tracer.buildSpan("test", "phased").start();
      assertEquals(1L, Snapshot.take().liveSince(before));

      // semantic conventions end live when end is called, not when the span is published
      assertTrue(span.phasedFinish());
      assertEquals(0L, Snapshot.take().liveSince(before));

      span.publish();
      Snapshot after = Snapshot.take();
      assertEquals(byOrigin(1, 0, 0), after.startedSince(before));
      assertEquals(0L, after.liveSince(before));
    } finally {
      tracer.close();
    }
  }

  private static ExtractedContext remoteParent(int samplingPriority) {
    return new ExtractedContext(
        DDTraceId.ONE,
        2,
        samplingPriority,
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

  /**
   * Builds a tracer whose remote communication stays paused, so the OTLP metrics service never
   * starts: its periodic collection would race the manual collections these tests assert on.
   */
  private CoreTracer newTracer() {
    return tracerBuilder()
        .writer(new ListWriter())
        .sharedCommunicationObjects(new SharedCommunicationObjects(true))
        .build();
  }

  /** One collection of {@code otel.sdk.span.started} by parent origin and {@code live}. */
  private static final class Snapshot {
    final Map<String, Long> started = new HashMap<>();
    final long live;

    private Snapshot(OtelMetricCollector collector) {
      for (DataPointEntry point :
          collector.points.getOrDefault("otel.sdk.span.started", emptyList())) {
        started.put((String) point.attrs.get("otel.span.parent.origin"), point.value.longValue());
      }
      // every origin is reported on every collection, 0 included
      assertEquals(byOrigin(0, 0, 0).keySet(), started.keySet());
      List<DataPointEntry> livePoints =
          collector.points.getOrDefault("otel.sdk.span.live", emptyList());
      assertEquals(1, livePoints.size());
      assertTrue(livePoints.get(0).attrs.isEmpty(), "live carries no attributes");
      live = livePoints.get(0).value.longValue();
    }

    static Snapshot take() {
      return new Snapshot(collect());
    }

    Map<String, Long> startedSince(Snapshot earlier) {
      Map<String, Long> delta = new HashMap<>();
      started.forEach((origin, value) -> delta.put(origin, value - earlier.started.get(origin)));
      return delta;
    }

    long totalStartedSince(Snapshot earlier) {
      long total = 0;
      for (long delta : startedSince(earlier).values()) {
        total += delta;
      }
      return total;
    }

    long liveSince(Snapshot earlier) {
      return live - earlier.live;
    }
  }
}
