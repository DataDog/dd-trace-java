package datadog.trace.core.processor.rule;

import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.ExclusiveSpan;
import datadog.trace.core.processor.TraceProcessor;

/** Converts error tag to field */
public class ErrorRule implements TraceProcessor.Rule {

  private static final Integer ONE = 1;

  @Override
  public String[] aliases() {
    return new String[] {"ErrorFlag"};
  }

  @Override
  public void processSpan(final ExclusiveSpan span) {
    final Object value = span.getAndRemoveTag(Tags.ERROR);
    if (value instanceof Boolean) {
      setError(span, (Boolean) value);
    } else if (value != null) {
      setError(span, Boolean.parseBoolean(value.toString()));
    }
  }

  private static void setError(ExclusiveSpan span, boolean error) {
    span.setError(error);
    if (error && span.isMeasured()) {
      // stop the trace agent from upscaling errors
      span.setMetric(DDSpanContext.SAMPLE_RATE_KEY, ONE);
    }
  }
}
