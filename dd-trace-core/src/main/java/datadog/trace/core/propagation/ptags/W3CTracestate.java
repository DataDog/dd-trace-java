package datadog.trace.core.propagation.ptags;

import datadog.trace.api.sampling.PrioritySampling;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * What merging a W3C {@code tracestate} into another context's propagation tags reads from it,
 * without building propagation tags for it. Obtained from {@link #parse(String)}.
 *
 * <p>When the tracestate has an {@code ot} member, the trace keeps this as its tracestate, and it
 * parses the member on first read under the pending checks of an {@link InheritedSamplingState}.
 */
public final class W3CTracestate {
  static final W3CTracestate NONE = new W3CTracestate(null, null, 0, PrioritySampling.UNSET, null);

  private static final byte POSITION_UNKNOWN = 0;
  private static final byte POSITION_PRESERVED = 1;
  private static final byte POSITION_REPLACED = 2;

  private static final AtomicReferenceFieldUpdater<W3CTracestate, Resolved> RESOLVED_UPDATER =
      AtomicReferenceFieldUpdater.newUpdater(W3CTracestate.class, Resolved.class, "resolved");

  /** The header value, or null if it's rejected. */
  final String tracestate;

  /** The {@code ot} member's value, unparsed, or null if there is none. */
  final CharSequence rawOtelTraceState;

  /** What the {@code ot} member contributes to the size of {@link #tracestate}. */
  final int otelOriginalSize;

  private final int samplingPriority;
  private final CharSequence lastParentId;

  /** The member parsed under the checks it was parsed for, or null until it's first read. */
  private volatile Resolved resolved;

  /** Whether the raw member, written at its position, leaves {@link #tracestate} unchanged. */
  private volatile byte rawPosition;

  W3CTracestate(
      String tracestate,
      CharSequence rawOtelTraceState,
      int otelOriginalSize,
      int samplingPriority,
      CharSequence lastParentId) {
    this.tracestate = tracestate;
    this.rawOtelTraceState = rawOtelTraceState;
    this.otelOriginalSize = otelOriginalSize;
    this.samplingPriority = samplingPriority;
    this.lastParentId = lastParentId;
  }

  /** Parses a {@code tracestate} header value, which may be null. */
  public static W3CTracestate parse(String value) {
    return W3CPTagsCodec.parseForMerge(value);
  }

  /** The sampling priority of the {@code dd} member's {@code s} element. */
  public int getSamplingPriority() {
    return samplingPriority;
  }

  /** The {@code dd} member's {@code p} element, or null. */
  public CharSequence getLastParentId() {
    return lastParentId;
  }

  /**
   * The {@code ot} member parsed, with {@code pendingChecks} applied. Racing readers agree on one
   * instance, so identity checks on otel states keep skipping redundant work.
   */
  OtelTraceState otelTraceState(byte pendingChecks) {
    Resolved current = resolved;
    if (current != null && current.pendingChecks == pendingChecks) {
      return current.otelTraceState;
    }
    OtelTraceState otelTraceState = OtelTraceState.parse(rawOtelTraceState, otelOriginalSize);
    if (InheritedSamplingState.checksSampled(pendingChecks)) {
      otelTraceState = checked(otelTraceState, true);
    }
    if (InheritedSamplingState.checksNotSampled(pendingChecks)) {
      otelTraceState = checked(otelTraceState, false);
    }
    if (RESOLVED_UPDATER.compareAndSet(
        this, current, new Resolved(pendingChecks, otelTraceState))) {
      return otelTraceState;
    }
    Resolved raced = resolved;
    return raced != null && raced.pendingChecks == pendingChecks
        ? raced.otelTraceState
        : otelTraceState;
  }

  /**
   * The {@code ot} member as it's written, equal in content to {@link #otelTraceState(byte)}: the
   * raw member when parsing it and applying the checks would leave it as is, which is the usual
   * case when relaying a trace unchanged, so injection doesn't have to parse it.
   */
  CharSequence otelTraceStateForWrite(byte pendingChecks) {
    if (!isResolved(pendingChecks)
        && OtelTraceState.isUnchangedByChecks(
            rawOtelTraceState,
            InheritedSamplingState.checksSampled(pendingChecks),
            InheritedSamplingState.checksNotSampled(pendingChecks))) {
      return rawOtelTraceState;
    }
    return otelTraceState(pendingChecks);
  }

  /** Whether the member has been parsed for {@code pendingChecks}. */
  boolean isResolved(byte pendingChecks) {
    Resolved current = resolved;
    return current != null && current.pendingChecks == pendingChecks;
  }

  /**
   * Whether writing {@code otelTraceState} at the {@code ot} member's position leaves the
   * tracestate unchanged. Cached for the raw member, which every injection of a relayed trace
   * writes.
   */
  boolean preservesOtelPosition(CharSequence otelTraceState) {
    if (otelTraceState != rawOtelTraceState) {
      return W3CPTagsCodec.isUnchangedInheritedOtelMember(tracestate, otelTraceState);
    }
    byte position = rawPosition;
    if (position == POSITION_UNKNOWN) {
      position =
          W3CPTagsCodec.isUnchangedInheritedOtelMember(tracestate, otelTraceState)
              ? POSITION_PRESERVED
              : POSITION_REPLACED;
      rawPosition = position;
    }
    return position == POSITION_PRESERVED;
  }

  private static OtelTraceState checked(OtelTraceState otelTraceState, boolean sampled) {
    return otelTraceState == null || otelTraceState.isConsistentWith(sampled)
        ? otelTraceState
        : otelTraceState.withoutThreshold();
  }

  /** A parse of the member and the checks it applied. */
  private static final class Resolved {
    final byte pendingChecks;
    final OtelTraceState otelTraceState;

    Resolved(byte pendingChecks, OtelTraceState otelTraceState) {
      this.pendingChecks = pendingChecks;
      this.otelTraceState = otelTraceState;
    }
  }
}
