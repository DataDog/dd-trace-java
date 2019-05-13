package datadog.opentracing.jfr;

import datadog.opentracing.DDSpanContext;

/** Event factory that returns {@link DDNoopSpanEvent} */
public final class DDNoopSpanEventFactory implements DDSpanEventFactory {
  @Override
  public DDSpanEvent create(final DDSpanContext context) {
    return new DDNoopSpanEvent();
  }
}
