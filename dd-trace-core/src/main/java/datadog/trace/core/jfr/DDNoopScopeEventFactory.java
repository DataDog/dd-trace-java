package datadog.trace.core.jfr;

import datadog.trace.core.DDSpanContext;

/** Event factory that returns {@link DDNoopScopeEvent} */
public final class DDNoopScopeEventFactory implements DDScopeEventFactory {
  @Override
  public DDScopeEvent create(final DDSpanContext context) {
    return DDNoopScopeEvent.INSTANCE;
  }
}
