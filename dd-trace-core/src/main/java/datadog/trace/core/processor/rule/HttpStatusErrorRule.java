package datadog.trace.core.processor.rule;

import datadog.trace.api.Config;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDSpan;
import datadog.trace.core.processor.TraceProcessor;
import java.util.Collection;
import java.util.Map;

public class HttpStatusErrorRule implements TraceProcessor.Rule {
  @Override
  public String[] aliases() {
    return new String[] {};
  }

  @Override
  public void processSpan(
      final DDSpan span, final Map<String, Object> tags, final Collection<DDSpan> trace) {
    final Number value = span.getMetrics().remove(Tags.HTTP_STATUS);
    if (value instanceof Integer) {
      span.setTag(Tags.HTTP_STATUS, Integer.toString(value.intValue()));
    }

    if (value != null && !span.context().getErrorFlag()) {
      try {
        final int status = value.intValue();
        if (DDSpanTypes.HTTP_SERVER.equals(span.getType())) {
          if (Config.get().getHttpServerErrorStatuses().contains(status)) {
            span.setError(true);
          }
        } else if (DDSpanTypes.HTTP_CLIENT.equals((span.getType()))) {
          if (Config.get().getHttpClientErrorStatuses().contains(status)) {
            span.setError(true);
          }
        }
      } catch (final NumberFormatException ex) {
        // If using Tags.HTTP_STATUS, value should always be an Integer,
        // but lets catch NumberFormatException just to be safe.
      }
    }
  }
}
