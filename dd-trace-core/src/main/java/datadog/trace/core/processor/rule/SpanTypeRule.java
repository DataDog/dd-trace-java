package datadog.trace.core.processor.rule;

import datadog.trace.api.DDTags;
import datadog.trace.core.ExclusiveSpan;
import datadog.trace.core.processor.TraceProcessor;

/** Converts span type tag to field */
public class SpanTypeRule implements TraceProcessor.Rule {
  @Override
  public String[] aliases() {
    return new String[] {"SpanTypeDecorator"};
  }

  @Override
  public void processSpan(final ExclusiveSpan span) {
    final Object type = span.getAndRemoveTag(DDTags.SPAN_TYPE);
    if (type != null) {
      span.setType(type.toString());
    }
  }
}
