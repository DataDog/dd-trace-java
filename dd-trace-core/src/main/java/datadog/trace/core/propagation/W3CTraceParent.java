package datadog.trace.core.propagation;

import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.DDSpanContext;

/**
 * Utility class for building W3C traceparent headers.
 *
 * @see <a href="https://www.w3.org/TR/trace-context/#traceparent-header">W3C Trace Context</a>
 */
public final class W3CTraceParent {

  private W3CTraceParent() {}

  /**
   * Builds a W3C traceparent header value from the given trace context components.
   *
   * <p>Format: {@code <version>-<traceId>-<spanId>-<flags>}
   *
   * @param traceId the trace id
   * @param spanId the span id
   * @param samplingPriority the sampling priority (positive values result in sampled flag set)
   * @return the W3C traceparent header value
   */
  public static String from(DDTraceId traceId, long spanId, int samplingPriority) {
    return "00-"
        + traceId.toHexString()
        + '-'
        + DDSpanId.toHexStringPadded(spanId)
        + (samplingPriority > 0 ? "-01" : "-00");
  }

  public static String from(AgentSpan span) {
    return from(span.getTraceId(), span.getSpanId(), span.context().getSamplingPriority());
  }

  public static String from(DDSpanContext spanContext) {
    return from(
        spanContext.getTraceId(), spanContext.getSpanId(), spanContext.getSamplingPriority());
  }
}
