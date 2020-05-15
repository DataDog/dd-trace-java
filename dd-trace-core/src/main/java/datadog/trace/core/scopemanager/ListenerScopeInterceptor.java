package datadog.trace.core.scopemanager;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.context.ScopeListener;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class ListenerScopeInterceptor extends DelegateScopeInterceptor {
  final List<ScopeListener> scopeListeners;

  public ListenerScopeInterceptor(
      final List<ScopeListener> scopeListeners, final ScopeInterceptor delegate) {
    super(delegate);
    this.scopeListeners = scopeListeners;
  }

  @Override
  public AgentScope handleSpan(final AgentSpan span) {
    final AgentScope wrapped = new NotifyingScope(delegate.handleSpan(span));
    for (final ScopeListener listener : scopeListeners) {
      listener.afterScopeActivated();
    }
    return wrapped;
  }

  private class NotifyingScope extends DelegatingScope {

    public NotifyingScope(final AgentScope delegate) {
      super(delegate);
    }

    @Override
    public void close() {
      super.close();
      for (final ScopeListener listener : scopeListeners) {
        listener.afterScopeClosed();
      }
    }
  }
}
