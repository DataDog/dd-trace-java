package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.DDTraceId;

/**
 * This interface describes a link to another span. The linked span could be part of the same trace
 * or not.
 */
public interface AgentSpanLink {
  /** The default trace flags (no flag enabled). */
  byte DEFAULT_FLAGS = 0;

  /** The sampled flag denotes that the caller may have recorded trace data. */
  byte SAMPLED_FLAG = 1;

  /**
   * Gets the trace identifier of the linked span.
   *
   * @return The trace identifier of the linked span.
   */
  DDTraceId traceId();

  /**
   * Gets the span identifier of the linked span.
   *
   * @return The span identifier of the linked span as an unsigned 64-bit long.
   */
  long spanId();

  /**
   * Gets the 8-bit field that controls tracing flags such as sampling, trace level, etc.
   *
   * @return The 8-bit field that controls tracing flags such as sampling, trace level, etc.
   * @see <a href="https://www.w3.org/TR/trace-context/#trace-flags">Trace flag header W3C
   *     Specification</a>
   */
  byte traceFlags();

  /**
   * Gets the vendor-specific trace information as defined per W3C standard.
   *
   * @return The vendor-specific trace state.
   * @see <a href="https://www.w3.org/TR/trace-context/#tracestate-header">Trace state header W3C
   *     Specification</a>
   */
  String traceState();

  /**
   * Gets the link attributes.
   *
   * @return The link attributes.
   */
  SpanAttributes attributes();
}
