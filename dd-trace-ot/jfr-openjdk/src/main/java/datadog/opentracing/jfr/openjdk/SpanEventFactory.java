package datadog.opentracing.jfr.openjdk;

import datadog.opentracing.DDSpanContext;
import datadog.opentracing.jfr.DDSpanEvent;
import datadog.opentracing.jfr.DDSpanEventFactory;

/** Event factory for {@link SpanEvent} */
public class SpanEventFactory implements DDSpanEventFactory {

  // This is needed to ensure SpanEvent class is loaded when SpanEventFactory is loaded
  // Loading SpanEvent is important because it also loads JFR classes - which may not be present on
  // some JVMs
  private final Class<?> eventClass;

  public SpanEventFactory() throws ClassNotFoundException {
    BlackList.checkBlackList();
    eventClass = Class.forName("datadog.opentracing.jfr.openjdk.SpanEvent");
  }

  @Override
  public DDSpanEvent create(final DDSpanContext context) {
    return new SpanEvent(context);
  }
}
