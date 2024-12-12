package com.datadog.debugger.codeorigin;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.propagation.PropagationTags;

public final class DebuggerConfiguration {
  public static boolean isDebuggerEnabled(AgentSpan span) {
    return "1".equals(getDebugLevel(span));
  }

  private static String getDebugLevel(AgentSpan span) {
    PropagationTags tags = ((DDSpanContext) span.getLocalRootSpan().context()).getPropagationTags();
    return tags.getDebugPropagation();
  }
}
