package com.datadog.debugger.codeorigin;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.propagation.PropagationTags;

public final class SpanDebug {
  public static boolean isSpanDebugEnabled(AgentSpan span) {
    boolean enabled = false;
    if (span.context() instanceof DDSpanContext) {
      String debug = getDebugLevel(span);
      enabled = debug != null && !("false".equals(debug) || "0".equals(debug));
    }

    return enabled;
  }

  public static boolean isSpanDebugDisabled(AgentSpan span) {
    boolean disabled = true;
    if (span.context() instanceof DDSpanContext) {
      String debug = getDebugLevel(span);
      disabled = "false".equals(debug) || "0".equals(debug);
    }

    return disabled;
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
