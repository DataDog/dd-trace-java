package datadog.trace.core.scopemanager;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.jfr.DDScopeEvent;
import datadog.trace.core.jfr.DDScopeEventFactory;
import lombok.extern.slf4j.Slf4j;

/** Manages events' lifecycle for JFR based on the start and stop of a particular scope */
@Slf4j
class EventScopeInterceptor extends ScopeInterceptor.DelegatingInterceptor {

  private final DDScopeEventFactory eventFactory;

  public EventScopeInterceptor(
      final DDScopeEventFactory eventFactory, final ScopeInterceptor delegate) {
    super(delegate);
    this.eventFactory = eventFactory;
  }

  @Override
  public Scope handleSpan(final AgentSpan span) {
    return new EventScope(span.context(), delegate.handleSpan(span));
  }

  private class EventScope extends DelegatingScope {

    private final DDScopeEvent event;

    public EventScope(final AgentSpan.Context context, final Scope delegate) {
      super(delegate);
      event = eventFactory.create(context);
    }

    @Override
    public void afterActivated() {
      super.afterActivated();
      event.start();
    }

    @Override
    public void close() {
      event.finish();
      super.close();
    }
  }
}
