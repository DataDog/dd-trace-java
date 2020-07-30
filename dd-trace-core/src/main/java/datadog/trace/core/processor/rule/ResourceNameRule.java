package datadog.trace.core.processor.rule;

import datadog.trace.api.DDTags;
import datadog.trace.core.ExclusiveSpan;
import datadog.trace.core.processor.TraceProcessor;

/** Converts resource name tag to field */
public class ResourceNameRule implements TraceProcessor.Rule {
  @Override
  public String[] aliases() {
    return new String[] {"ResourceNameDecorator"};
  }

  @Override
  public void processSpan(final ExclusiveSpan span) {
    final Object name = span.getAndRemoveTag(DDTags.RESOURCE_NAME);
    if (name instanceof CharSequence) {
      span.setResourceName((CharSequence) name);
    } else if (name != null) {
      span.setResourceName(name.toString());
    }
  }
}
