package datadog.trace.core.scopemanager;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.context.TraceScope;

abstract class DelegateScopeInterceptor implements ScopeInterceptor {
  protected final ScopeInterceptor delegate;

  protected DelegateScopeInterceptor(final ScopeInterceptor delegate) {
    if (delegate != null) {
      this.delegate = delegate;
    } else {
      this.delegate =
          new ScopeInterceptor() {
            @Override
            public AgentScope handleSpan(final AgentSpan span) {
              return new SimpleScope(span);
            }
          };
    }
  }

  private static class SimpleScope implements AgentScope {
    private final AgentSpan span;

    SimpleScope(final AgentSpan span) {
      this.span = span;
    }

    @Override
    public AgentSpan span() {
      return span;
    }

    @Override
    public void setAsyncPropagation(final boolean value) {}

    @Override
    public TraceScope.Continuation capture() {
      return null;
    }

    @Override
    public void close() {}

    @Override
    public boolean isAsyncPropagating() {
      return false;
    }
  }
}
