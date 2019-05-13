package datadog.opentracing.jfr.openjdk;

import datadog.opentracing.DDSpanContext;
import datadog.opentracing.jfr.DDSpanEvent;
import datadog.opentracing.jfr.DDSpanEventFactory;

/** Event factory for {@link SpanEvent} */
public class SpanEventFactory implements DDSpanEventFactory {
  @Override
  public DDSpanEvent create(final DDSpanContext context) {
    return new SpanEvent(context);
  }
}
