package datadog.trace.core.processor.rule;

import datadog.trace.api.DDTags;
import datadog.trace.core.ExclusiveSpan;
import datadog.trace.core.processor.TraceProcessor;

public class ContextStackRule implements TraceProcessor.Rule {
  @Override
  public String[] aliases() {
    return new String[] {};
  }

  @Override
  public void processSpan(final ExclusiveSpan span) {
    final StackTraceElement[] stack = span.getContextStack();
    if (stack != null) {
      StringBuilder builder = new StringBuilder("Span Context");
      for (StackTraceElement traceElement : stack) {
        builder.append("\tat " + traceElement);
      }
      span.setTag(DDTags.CONTEXT_STACK_TAG, builder.toString());
    }
  }
}
