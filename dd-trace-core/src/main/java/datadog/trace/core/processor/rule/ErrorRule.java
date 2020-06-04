package datadog.trace.core.processor.rule;

import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDSpan;
import datadog.trace.core.processor.TraceProcessor;

/** Converts error tag to field */
public class ErrorRule implements TraceProcessor.Rule {
  @Override
  public String[] aliases() {
    return new String[] {"ErrorFlag"};
  }

  @Override
  public void processSpan(final DDSpan span) {
    final Object value = span.getAndRemoveTag(Tags.ERROR);
    if (value instanceof Boolean) {
      span.setError((Boolean) value);
    } else if (value != null) {
      span.setError(Boolean.parseBoolean(value.toString()));
    }
  }
}
