package datadog.trace.core.otlp.sdk;

import static datadog.trace.bootstrap.otel.api.common.AttributeKey.stringKey;
import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.COUNTER;
import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.UP_DOWN_COUNTER;

import datadog.trace.bootstrap.otel.api.common.AttributeKey;
import datadog.trace.bootstrap.otel.api.common.Attributes;
import datadog.trace.bootstrap.otel.metrics.OtelInstrumentBuilder;
import datadog.trace.bootstrap.otel.metrics.OtelInstrumentType;
import datadog.trace.bootstrap.otel.metrics.data.OtelMetricStorage;
import datadog.trace.core.DDSpan;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.monitor.SpanLifecycleMetrics;
import java.util.concurrent.atomic.LongAdder;

/**
 * Records {@code otel.sdk.span.started}, split by parent origin, and {@code otel.sdk.span.live},
 * which semantic conventions do not split by parent origin. The hot path only touches {@link
 * LongAdder}s; observable instruments report the adders at collection.
 */
final class OtelSpanLifecycleMetrics extends SpanLifecycleMetrics {
  static final AttributeKey<String> PARENT_ORIGIN = stringKey("otel.span.parent.origin");

  /** Indexed by {@link DDSpanContext#getParentOrigin()}, as is the {@code started} adder array. */
  private static final Attributes[] ORIGINS = {
    Attributes.of(PARENT_ORIGIN, "none"),
    Attributes.of(PARENT_ORIGIN, "local"),
    Attributes.of(PARENT_ORIGIN, "remote")
  };

  private static final Attributes[] NO_ATTRIBUTES = {Attributes.empty()};

  /**
   * One per JVM: the observables read these adders, and a second set would double-report. Declared
   * after the attribute constants, so they exist before the constructor registers the observables.
   */
  static final OtelSpanLifecycleMetrics INSTANCE = new OtelSpanLifecycleMetrics();

  private final LongAdder[] started = {new LongAdder(), new LongAdder(), new LongAdder()};
  private final LongAdder live = new LongAdder();

  private OtelSpanLifecycleMetrics() {
    observe("otel.sdk.span.started", COUNTER, "The number of created spans.", started, ORIGINS);
    observe(
        "otel.sdk.span.live",
        UP_DOWN_COUNTER,
        "The number of created spans with recording=true for which the end operation has not"
            + " been called yet.",
        new LongAdder[] {live},
        NO_ATTRIBUTES);
  }

  @Override
  public void onSpanStarted(DDSpan span) {
    started[span.spanContext().getParentOrigin()].increment();
    live.increment();
  }

  @Override
  public void onSpanEnded(DDSpan span) {
    live.decrement();
  }

  /**
   * Records every series on each collection, 0 included: a skipped observable series is dropped,
   * and when it returns, a delta export would report its whole cumulative count.
   */
  private static void observe(
      String name,
      OtelInstrumentType type,
      String description,
      LongAdder[] adders,
      Attributes[] attributes) {
    OtelMetricStorage storage =
        OtelSdkRegistry.storage(
            OtelInstrumentBuilder.ofLongs(name, type),
            true,
            description,
            "{span}",
            OtelMetricStorage::newLongDeltaStorage);
    OtelSdkRegistry.observe(() -> {
      for (int i = 0; i < adders.length; i++) {
        storage.recordLong(adders[i].sum(), attributes[i]);
      }
    });
  }
}
