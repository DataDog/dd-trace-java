package datadog.trace.core.propagation.ptags;

import datadog.trace.core.propagation.PropagationTags.SamplingState;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * The sampling state for an extracted trace whose OpenTelemetry {@code ot} tracestate member hasn't
 * been parsed yet. Extraction used to parse the member and check it against the inherited decision
 * on the request thread, though only W3C injection, OTLP export and a local change of decision ever
 * read it. This keeps the raw member and parses it on first read, so a leaf service exporting to
 * the Datadog agent never pays for it.
 *
 * <p>Consistency checks made before the first read are recorded as pending and applied when the
 * member is parsed. Each check drops the threshold when {@code rv >= th} doesn't match the sampling
 * decision checked against, and dropping it is idempotent and leaves the state consistent with any
 * decision. So any sequence of checks has the same effect as one per decision checked, in any
 * order, and two bits record them without changing the output.
 */
final class InheritedSamplingState extends SamplingState {
  static final byte NO_CHECKS = 0;
  private static final byte CHECKED_SAMPLED = 1;
  private static final byte CHECKED_NOT_SAMPLED = 2;

  /** Marks a member that parsed to nothing, distinct from not parsed yet. */
  private static final Object NO_OTEL_TRACE_STATE = new Object();

  private static final AtomicReferenceFieldUpdater<InheritedSamplingState, Object>
      RESOLVED_UPDATER =
          AtomicReferenceFieldUpdater.newUpdater(
              InheritedSamplingState.class, Object.class, "resolved");

  private final CharSequence rawOtelTraceState;
  private final int otelOriginalSize;
  private final byte pendingChecks;

  /** {@code null} until parsed, then the {@link OtelTraceState} or {@link #NO_OTEL_TRACE_STATE}. */
  private volatile Object resolved;

  InheritedSamplingState(
      int samplingPriority,
      String tracestate,
      CharSequence decisionMaker,
      CharSequence knuthSamplingRate,
      CharSequence rawOtelTraceState,
      int otelOriginalSize,
      byte pendingChecks) {
    super(samplingPriority, tracestate, null, decisionMaker, knuthSamplingRate);
    this.rawOtelTraceState = rawOtelTraceState;
    this.otelOriginalSize = otelOriginalSize;
    this.pendingChecks = pendingChecks;
  }

  /** Returns the pending checks with one more against {@code sampled} added. */
  static byte withCheck(byte pendingChecks, boolean sampled) {
    return (byte) (pendingChecks | (sampled ? CHECKED_SAMPLED : CHECKED_NOT_SAMPLED));
  }

  /** A copy of this unparsed member under a new decision, with one more pending check. */
  InheritedSamplingState withCheck(
      int samplingPriority,
      String tracestate,
      CharSequence decisionMaker,
      CharSequence knuthSamplingRate,
      boolean sampled) {
    return new InheritedSamplingState(
        samplingPriority,
        tracestate,
        decisionMaker,
        knuthSamplingRate,
        rawOtelTraceState,
        otelOriginalSize,
        withCheck(pendingChecks, sampled));
  }

  boolean isResolved() {
    return resolved != null;
  }

  /**
   * Writes the raw member when parsing it and applying the pending checks would leave it as is,
   * which is the usual case when relaying a trace unchanged, so injection doesn't have to parse it.
   */
  @Override
  public CharSequence getOtelTraceStateForWrite() {
    if (resolved == null
        && OtelTraceState.isUnchangedByChecks(
            rawOtelTraceState,
            (pendingChecks & CHECKED_SAMPLED) != 0,
            (pendingChecks & CHECKED_NOT_SAMPLED) != 0)) {
      return rawOtelTraceState;
    }
    return getOtelTraceState();
  }

  CharSequence rawOtelTraceState() {
    return rawOtelTraceState;
  }

  int otelOriginalSize() {
    return otelOriginalSize;
  }

  byte pendingChecks() {
    return pendingChecks;
  }

  /**
   * Parses the member and applies the pending checks on first read. Racing readers agree on one
   * instance, so identity checks on otel states keep skipping redundant work.
   */
  @Override
  public CharSequence getOtelTraceState() {
    Object current = resolved;
    if (current == null) {
      current = resolve();
    }
    return current == NO_OTEL_TRACE_STATE ? null : (OtelTraceState) current;
  }

  private Object resolve() {
    OtelTraceState otelTraceState = OtelTraceState.parse(rawOtelTraceState, otelOriginalSize);
    if ((pendingChecks & CHECKED_SAMPLED) != 0) {
      otelTraceState = checked(otelTraceState, true);
    }
    if ((pendingChecks & CHECKED_NOT_SAMPLED) != 0) {
      otelTraceState = checked(otelTraceState, false);
    }
    Object built = otelTraceState == null ? NO_OTEL_TRACE_STATE : otelTraceState;
    return RESOLVED_UPDATER.compareAndSet(this, null, built) ? built : resolved;
  }

  private static OtelTraceState checked(OtelTraceState otelTraceState, boolean sampled) {
    return otelTraceState == null || otelTraceState.isConsistentWith(sampled)
        ? otelTraceState
        : otelTraceState.withoutThreshold();
  }
}
