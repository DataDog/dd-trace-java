package datadog.trace.core.processor.rule;

import datadog.trace.bootstrap.instrumentation.api.Tags;
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
      boolean skipNext = true;
      for (StackTraceElement element : stack) {
        final boolean tracingClass = element.getClassName().startsWith("datadog.trace.core.");
        if (skipNext || tracingClass) {
          skipNext = tracingClass;
          continue;
        }
        builder.append("\tat ");
        builder.append(element);
        builder.append("\n");
      }
      span.setTag(Tags.CONTEXT_STACK_TAG, builder);
    }
  }
}
