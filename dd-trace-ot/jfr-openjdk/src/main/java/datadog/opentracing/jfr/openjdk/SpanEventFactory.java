package datadog.opentracing.jfr.openjdk;

import datadog.opentracing.DDSpanContext;
import datadog.opentracing.jfr.DDSpanEvent;
import datadog.opentracing.jfr.DDSpanEventFactory;

/** Event factory for {@link SpanEvent} */
public class SpanEventFactory implements DDSpanEventFactory {

  // This is needed to ensure SpanEven class is loaded when SpanEventFactory is loaded
  // Loading SpanEven is important because it also loads JFR classes - which may not be present on
  // some JVMs
  private static final Class<?> EVENT_CLASS = SpanEvent.class;

  @Override
  public DDSpanEvent create(final DDSpanContext context) {
    return new SpanEvent(context);
  }
}
