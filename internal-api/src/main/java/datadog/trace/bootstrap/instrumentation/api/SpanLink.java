package datadog.trace.bootstrap.instrumentation.api;

import static datadog.trace.bootstrap.instrumentation.api.SpanAttributes.EMPTY;

import datadog.trace.api.DDTraceId;

/** This class is a base implementation of {@link AgentSpanLink}. */
public class SpanLink implements AgentSpanLink {
  private final DDTraceId traceId;
  private final long spanId;
  private final byte traceFlags;
  private final String traceState;
  private final AgentSpan.Attributes attributes;

  protected SpanLink(
      DDTraceId traceId,
      long spanId,
      byte traceFlags,
      String traceState,
      AgentSpan.Attributes attributes) {
    this.traceId = traceId == null ? DDTraceId.ZERO : traceId;
    this.spanId = spanId;
    this.traceFlags = traceFlags;
    this.traceState = traceState == null ? "" : traceState;
    this.attributes = attributes == null ? EMPTY : attributes;
  }

  /**
   * Creates a span link from a span context. Gathers the trace and span identifiers from the given
   * instance.
   *
   * @param context The context of the span to get the link to.
   * @return A span link to the given context.
   */
  public static SpanLink from(AgentSpan.Context context) {
    return from(context, DEFAULT_FLAGS, "", EMPTY);
  }

  /**
   * Creates a span link from a span context with W3C trace state and custom attributes. Gathers the
   * trace and span identifiers from the given instance.
   *
   * @param context The context of the span to get the link to.
   * @param traceFlags The W3C formatted trace flags.
   * @param traceState The W3C formatted trace state.
   * @param attributes The link attributes.
   * @return A span link to the given context.
   */
  public static SpanLink from(
      AgentSpan.Context context,
      byte traceFlags,
      String traceState,
      AgentSpan.Attributes attributes) {
    if (context.getSamplingPriority() > 0) {
      traceFlags = (byte) (traceFlags | SAMPLED_FLAG);
    }
    return new SpanLink(
        context.getTraceId(), context.getSpanId(), traceFlags, traceState, attributes);
  }

  @Override
  public DDTraceId traceId() {
    return this.traceId;
  }

  @Override
  public long spanId() {
    return this.spanId;
  }

  @Override
  public byte traceFlags() {
    return this.traceFlags;
  }

  @Override
  public String traceState() {
    return this.traceState;
  }

  @Override
  public AgentSpan.Attributes attributes() {
    return this.attributes;
  }

  @Override
  public String toString() {
    return "SpanLink{"
        + "traceId="
        + this.traceId
        + ", spanId="
        + this.spanId
        + ", traceFlags="
        + this.traceFlags
        + ", traceState='"
        + this.traceState
        + '\''
        + ", attributes="
        + this.attributes
        + '}';
  }
}
