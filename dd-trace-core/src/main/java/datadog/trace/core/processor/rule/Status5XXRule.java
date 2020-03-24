package datadog.trace.core.processor.rule;

<<<<<<< HEAD:dd-trace-core/src/main/java/datadog/trace/core/processor/rule/HttpStatusErrorRule.java
import datadog.opentracing.DDSpan;
import datadog.trace.api.Config;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.common.processor.TraceProcessor;
=======
import datadog.trace.core.DDSpan;
import datadog.trace.core.processor.TraceProcessor;
>>>>>>> 820c8132c... Move to core project and datadog.trace.core packages:dd-trace-core/src/main/java/datadog/trace/core/processor/rule/Status5XXRule.java
import io.opentracing.tag.Tags;
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
    final Object value = tags.get(Tags.HTTP_STATUS.getKey());
    if (value != null && !span.context().getErrorFlag()) {
      try {
        final int status =
            value instanceof Integer ? (int) value : Integer.parseInt(value.toString());
        if (span.getType().equals(DDSpanTypes.HTTP_SERVER)) {
          if (Config.get().getHttpServerErrorStatuses().contains(status)) {
            span.setError(true);
          }
        } else if (span.getType().equals(DDSpanTypes.HTTP_CLIENT)) {
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
