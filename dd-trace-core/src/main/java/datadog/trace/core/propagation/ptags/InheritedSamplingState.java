package datadog.trace.core.propagation.ptags;

import datadog.trace.core.propagation.PropagationTags.SamplingState;

/**
 * The sampling state for an extracted trace whose OpenTelemetry {@code ot} tracestate member hasn't
 * been parsed yet. Extraction used to parse the member and check it against the inherited decision
 * on the request thread, though only W3C injection, OTLP export and a local change of decision ever
 * read it. The member stays raw in the trace's {@link W3CTracestate}, which parses it on first
 * read, so a leaf service exporting to the Datadog agent never pays for it.
 *
 * <p>This state records only the decision and the consistency checks made before the first read,
 * which are applied when the member is parsed. Each check drops the threshold when {@code rv >= th}
 * doesn't match the sampling decision checked against, and dropping it is idempotent and leaves the
 * state consistent with any decision. So any sequence of checks has the same effect as one per
 * decision checked, in any order, and two bits record them without changing the output.
 *
 * <p>Holding no per-trace data, these states are shared like other states. The member is read
 * through the trace's propagation tags; this state can't read it itself.
 */
final class InheritedSamplingState extends SamplingState {
  static final byte NO_CHECKS = 0;
  private static final byte CHECKED_SAMPLED = 1;
  private static final byte CHECKED_NOT_SAMPLED = 2;

  /** The number of distinct pending check values. */
  static final int CHECK_COMBINATIONS = 4;

  private final byte pendingChecks;

  InheritedSamplingState(
      int samplingPriority,
      CharSequence decisionMaker,
      CharSequence knuthSamplingRate,
      byte pendingChecks) {
    super(samplingPriority, null, decisionMaker, knuthSamplingRate);
    this.pendingChecks = pendingChecks;
  }

  /** Returns the pending checks with one more against {@code sampled} added. */
  static byte withCheck(byte pendingChecks, boolean sampled) {
    return (byte) (pendingChecks | (sampled ? CHECKED_SAMPLED : CHECKED_NOT_SAMPLED));
  }

  static boolean checksSampled(byte pendingChecks) {
    return (pendingChecks & CHECKED_SAMPLED) != 0;
  }

  static boolean checksNotSampled(byte pendingChecks) {
    return (pendingChecks & CHECKED_NOT_SAMPLED) != 0;
  }

  byte pendingChecks() {
    return pendingChecks;
  }

  @Override
  public CharSequence getOtelTraceState() {
    throw new UnsupportedOperationException(
        "the ot member is held by the trace; read it through its propagation tags");
  }

  @Override
  public CharSequence getOtelTraceStateForWrite() {
    throw new UnsupportedOperationException(
        "the ot member is held by the trace; read it through its propagation tags");
  }
}
