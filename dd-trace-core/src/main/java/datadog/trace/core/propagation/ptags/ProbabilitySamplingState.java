package datadog.trace.core.propagation.ptags;

import datadog.trace.core.propagation.PropagationTags.SamplingState;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * The sampling state for a fresh probability decision, one with no inherited OpenTelemetry
 * tracestate. Every such root decision used to build its {@link OtelTraceState} up front, though
 * only W3C injection and OTLP export ever read it. This keeps the two inputs instead and builds the
 * {@code ot} member on first read, so a trace that is never injected or exported over OTLP doesn't
 * pay for it.
 */
final class ProbabilitySamplingState extends SamplingState {
  private static final AtomicReferenceFieldUpdater<ProbabilitySamplingState, OtelTraceState>
      OTEL_TRACE_STATE_UPDATER =
          AtomicReferenceFieldUpdater.newUpdater(
              ProbabilitySamplingState.class, OtelTraceState.class, "otelTraceState");

  private final long traceIdLowOrderBits;
  private final double sampleRate;

  private volatile OtelTraceState otelTraceState;

  ProbabilitySamplingState(
      int samplingPriority,
      String tracestate,
      CharSequence decisionMaker,
      CharSequence knuthSamplingRate,
      long traceIdLowOrderBits,
      double sampleRate) {
    super(samplingPriority, tracestate, null, decisionMaker, knuthSamplingRate);
    this.traceIdLowOrderBits = traceIdLowOrderBits;
    this.sampleRate = sampleRate;
  }

  /**
   * Builds the {@code ot} member on first read. Racing readers agree on one instance, so identity
   * checks on otel states keep skipping redundant work, and publication doesn't depend on {@link
   * OtelTraceState} keeping its fields final.
   */
  @Override
  public CharSequence getOtelTraceState() {
    OtelTraceState current = otelTraceState;
    if (current != null) {
      return current;
    }
    OtelTraceState built =
        OtelTraceState.fromProbabilityDecision(
            traceIdLowOrderBits, sampleRate, getSamplingPriority());
    return OTEL_TRACE_STATE_UPDATER.compareAndSet(this, null, built) ? built : otelTraceState;
  }
}
