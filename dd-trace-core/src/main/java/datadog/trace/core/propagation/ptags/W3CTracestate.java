package datadog.trace.core.propagation.ptags;

import datadog.trace.api.sampling.PrioritySampling;

/**
 * What merging a W3C {@code tracestate} into another context's propagation tags reads from it,
 * without building propagation tags for it. Obtained from {@link #parse(String)}.
 */
public final class W3CTracestate {
  static final W3CTracestate NONE = new W3CTracestate(null, null, 0, PrioritySampling.UNSET, null);

  /** The header value, or null if it's rejected. */
  final String tracestate;

  /** The {@code ot} member's value, unparsed, or null if there is none. */
  final CharSequence rawOtelTraceState;

  /** What the {@code ot} member contributes to the size of {@link #tracestate}. */
  final int otelOriginalSize;

  private final int samplingPriority;
  private final CharSequence lastParentId;

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
}
