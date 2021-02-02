package datadog.trace.core.jfr;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

/** Event factory that returns {@link DDNoopScopeEvent} */
public final class DDNoopScopeEventFactory implements DDScopeEventFactory {

  public static final DDNoopScopeEventFactory INSTANCE = new DDNoopScopeEventFactory();

  private DDNoopScopeEventFactory() {}

  @Override
  public DDScopeEvent create(final AgentSpan.Context context) {
    return DDNoopScopeEvent.INSTANCE;
  }
}
