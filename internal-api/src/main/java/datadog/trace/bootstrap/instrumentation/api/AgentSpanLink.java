package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.DDTraceId;
import java.util.Map;

/**
 * This interface describes a link to another span. The linked span could be part of the same trace
 * or not.
 */
public interface AgentSpanLink {
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
   * Gets the vendor-specific trace information as defined per W3C standard.
   *
   * @return The vendor-specific trace state.
   * @see <a href="https://www.w3.org/TR/trace-context/#tracestate-header">Trace state header W3C
   *     Specification</a>
   */
  String traceState();

  /**
   * Gets an immutable collection of the link attributes.
   *
   * @return The link attributes, wrapped into an immutable collection.
   */
  Map<String, String> attributes();
}
