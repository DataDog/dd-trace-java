package datadog.trace.core.scopemanager;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.context.ScopeListener;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class ListenerScopeInterceptor extends ScopeInterceptor.DelegateScopeInterceptor {
  final List<ScopeListener> scopeListeners;

  public ListenerScopeInterceptor(
      final List<ScopeListener> scopeListeners, final ScopeInterceptor delegate) {
    super(delegate);
    this.scopeListeners = scopeListeners;
  }

  @Override
  public DDScope handleSpan(final AgentSpan span) {
    if (scopeListeners.isEmpty()) {
      return delegate.handleSpan(span);
    }
    return new NotifyingScope(delegate.handleSpan(span));
  }

  private class NotifyingScope extends DelegatingScope {
    public NotifyingScope(final DDScope delegate) {
      super(delegate);
    }

    @Override
    public void afterActivated() {
      super.afterActivated();
      for (final ScopeListener listener : scopeListeners) {
        listener.afterScopeActivated();
      }
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
