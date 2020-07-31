package datadog.trace.core.processor.rule;

import datadog.trace.api.Config;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.ExclusiveSpan;
import datadog.trace.core.processor.TraceProcessor;

public class HttpStatusErrorRule implements TraceProcessor.Rule {
  @Override
  public String[] aliases() {
    return new String[] {};
  }

  @Override
  public void processSpan(final ExclusiveSpan span) {
    final Object value = span.getTag(Tags.HTTP_STATUS);
    if (value != null && !span.isError()) {
      try {
        final int status =
            value instanceof Integer ? (int) value : Integer.parseInt(value.toString());
        if (DDSpanTypes.HTTP_SERVER.equals(span.getType())) {
          if (Config.get().getHttpServerErrorStatuses().get(status)) {
            span.setError(true);
          }
        } else if (DDSpanTypes.HTTP_CLIENT.equals((span.getType()))) {
          if (Config.get().getHttpClientErrorStatuses().get(status)) {
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
