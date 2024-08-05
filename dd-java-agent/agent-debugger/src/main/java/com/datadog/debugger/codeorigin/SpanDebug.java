package com.datadog.debugger.codeorigin;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.propagation.PropagationTags;

public final class SpanDebug {
  public static boolean isSpanDebugEnabled(AgentSpan span) {
    if (span.context() instanceof DDSpanContext) {
      String debug = getDebugLevel(span);
      return debug != null && !("false".equals(debug) || "0".equals(debug));
    }

    return false;
  }

  public static boolean isSpanDebugDisabled(AgentSpan span) {
    if (span.context() instanceof DDSpanContext) {
      String debug = getDebugLevel(span);
      return "false".equals(debug) || "0".equals(debug);
    }

    return true;
  }

  private static String getDebugLevel(AgentSpan span) {
    PropagationTags tags = ((DDSpanContext) span.context()).getPropagationTags();
    return tags.getDebugPropagation();
  }

  public static void enableDebug(AgentSpan span) {
    if (span.context() instanceof DDSpanContext) {
      DDSpanContext context = (DDSpanContext) span.context();
      PropagationTags tags = context.getPropagationTags();

      tags.updateDebugPropagation("1");
    }
  }
}
