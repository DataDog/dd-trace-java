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

  private static final int TRACE_PARENT_LENGTH = 55;

  private W3CTraceParent() {}

  /**
   * Builds a W3C traceparent header value from the given trace context components.
   *
   * <p>Format: {@code <version>-<traceId>-<spanId>-<flags>}
   *
   * @param traceId the trace id
   * @param spanId the span id
   * @param isSampled whether the trace was sampled or not
   * @return the W3C traceparent header value
   */
  public static String from(DDTraceId traceId, long spanId, boolean isSampled) {
    StringBuilder sb = new StringBuilder(TRACE_PARENT_LENGTH);
    sb.append("00-");
    sb.append(traceId.toHexString());
    sb.append('-');
    sb.append(DDSpanId.toHexStringPadded(spanId));
    sb.append(isSampled ? "-01" : "-00");

    return sb.toString();
  }

  public static String from(AgentSpan span) {
    return from(span.getTraceId(), span.getSpanId(), span.context().getSamplingPriority() > 0);
  }

  public static String from(DDSpanContext spanContext) {
    return from(
        spanContext.getTraceId(), spanContext.getSpanId(), spanContext.getSamplingPriority() > 0);
  }
}
