package datadog.trace.core.scopemanager;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.context.ScopeListener;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/** This class is responsible for calling the ScopeListeners when the scope changes. */
@Slf4j
class ListenerScopeInterceptor extends ScopeInterceptor.DelegatingInterceptor {
  final List<ScopeListener> scopeListeners;

  public ListenerScopeInterceptor(
      final List<ScopeListener> scopeListeners, final ScopeInterceptor delegate) {
    super(delegate);
    this.scopeListeners = scopeListeners;
  }

  @Override
  public Scope handleSpan(final AgentSpan span) {
    if (scopeListeners.isEmpty()) {
      return delegate.handleSpan(span);
    }
    return new NotifyingScope(delegate.handleSpan(span));
  }

  private class NotifyingScope extends DelegatingScope {
    public NotifyingScope(final Scope delegate) {
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
      /**
       * We could check if a new span is active and call afterActivated again to maintain prior
       * semantics, but this causes extra modifications for our primary use case -- Logging MDC
       * updates, which have copy-on-write semantics, so the extra modifications are undesirable.
       */
    }
  }
}
