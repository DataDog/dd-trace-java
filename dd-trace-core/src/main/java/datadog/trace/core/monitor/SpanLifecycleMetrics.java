package datadog.trace.core.monitor;

import datadog.trace.core.DDSpan;

/**
 * Span lifecycle hooks for the OTel SDK self-monitoring metrics {@code otel.sdk.span.started} and
 * {@code otel.sdk.span.live}. Kept apart from {@link HealthMetrics}, which stays the DogStatsD
 * sink: only these two callbacks map to OTel metrics.
 */
public abstract class SpanLifecycleMetrics {
  public static final SpanLifecycleMetrics NO_OP = new SpanLifecycleMetrics() {};

  /** Called once when a span is registered with its trace. */
  public void onSpanStarted(DDSpan span) {}

  /** Called once when a span finishes. */
  public void onSpanEnded(DDSpan span) {}
}
