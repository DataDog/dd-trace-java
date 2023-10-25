package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.DDTraceId;
import java.util.Collections;
import java.util.Map;

/** This class is a base implementation of {@link AgentSpanLink}. */
public class SpanLink implements AgentSpanLink {
  private final DDTraceId traceId;
  private final long spanId;
  private final String traceState;
  private final Map<String, String> attributes;

  protected SpanLink(
      DDTraceId traceId, long spanId, String traceState, Map<String, String> attributes) {
    this.traceId = traceId;
    this.spanId = spanId;
    this.traceState = traceState;
    this.attributes = attributes == null ? Collections.emptyMap() : attributes;
  }

  /**
   * Creates a span link from a span context. Gathers the trace and span identifiers from the given
   * instance.
   *
   * @param context The context of the span to get the link to.
   * @return A span link to the given context.
   */
  public static SpanLink from(AgentSpan.Context context) {
    return from(context, "", Collections.emptyMap());
  }

  /**
   * Creates a span link from a span context with W3C trace state and custom attributes. Gathers the
   * trace and span identifiers from the given instance.
   *
   * @param context The context of the span to get the link to.
   * @param traceState The W3C formatted trace state.
   * @param attributes The link attributes.
   * @return A span link to the given context.
   */
  public static SpanLink from(
      AgentSpan.Context context, String traceState, Map<String, String> attributes) {
    return new SpanLink(context.getTraceId(), context.getSpanId(), traceState, attributes);
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
  public String traceState() {
    return this.traceState;
  }

  @Override
  public Map<String, String> attributes() {
    return this.attributes;
  }
}
