package datadog.trace.core.propagation;

import datadog.trace.api.DDSpanId;
import datadog.trace.core.DDSpanContext;

public class PropagationUtils {

  static final String TRACE_PARENT_KEY = "traceparent";
  static final int TRACE_PARENT_TID_START = 2 + 1;
  static final int TRACE_PARENT_TID_END = TRACE_PARENT_TID_START + 32;
  static final int TRACE_PARENT_SID_START = TRACE_PARENT_TID_END + 1;
  static final int TRACE_PARENT_SID_END = TRACE_PARENT_SID_START + 16;
  static final int TRACE_PARENT_FLAGS_START = TRACE_PARENT_SID_END + 1;
  static final int TRACE_PARENT_LENGTH = TRACE_PARENT_FLAGS_START + 2;

  static StringBuilder traceParent(final DDSpanContext context) {
    StringBuilder sb = new StringBuilder(TRACE_PARENT_LENGTH);
    sb.append("00-");
    sb.append(context.getTraceId().toHexStringPaddedOrOriginal(32));
    sb.append("-");
    sb.append(DDSpanId.toHexStringPadded(context.getSpanId()));
    sb.append(context.getSamplingPriority() > 0 ? "-01" : "-00");
    return sb;
  }
}
