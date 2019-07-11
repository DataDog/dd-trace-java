package datadog.opentracing.jfr.openjdk;

import datadog.opentracing.DDSpanContext;
import datadog.opentracing.jfr.DDScopeEvent;
import datadog.opentracing.jfr.DDScopeEventFactory;

/** Event factory for {@link ScopeEvent} */
public class ScopeEventFactory implements DDScopeEventFactory {

  // This is needed to ensure ScopeEvent class is loaded when SpanEventFactory is loaded
  // Loading ScopeEvent is important because it also loads JFR classes - which may not be present on
  // some JVMs
  private final Class<?> eventClass;

  public ScopeEventFactory() throws ClassNotFoundException {
    BlackList.checkBlackList();
    eventClass = Class.forName("datadog.opentracing.jfr.openjdk.ScopeEvent");
  }

  @Override
  public DDScopeEvent create(final DDSpanContext context) {
    return new ScopeEvent(context);
  }
}
