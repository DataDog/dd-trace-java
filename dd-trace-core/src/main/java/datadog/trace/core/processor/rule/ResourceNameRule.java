package datadog.trace.core.processor.rule;

import datadog.trace.api.DDTags;
import datadog.trace.core.DDSpan;
import datadog.trace.core.processor.TraceProcessor;
import java.util.Collection;
import java.util.Map;

/** Converts resource name tag to field */
public class ResourceNameRule implements TraceProcessor.Rule {
  @Override
  public String[] aliases() {
    return new String[] {"ResourceNameDecorator"};
  }

  @Override
  public void processSpan(
      final DDSpan span, final Map<String, Object> tags, final Collection<DDSpan> trace) {
    final Object name = span.getAndRemoveTag(DDTags.RESOURCE_NAME);
    if (name != null) {
      span.setResourceName(name.toString());
    }
  }
}
