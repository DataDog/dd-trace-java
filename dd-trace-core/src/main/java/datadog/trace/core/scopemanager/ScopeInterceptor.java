package datadog.trace.core.scopemanager;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.context.TraceScope;

interface ScopeInterceptor {
  DDScope handleSpan(AgentSpan span);

  abstract class DelegateScopeInterceptor implements ScopeInterceptor {
    protected final ScopeInterceptor delegate;

    protected DelegateScopeInterceptor(final ScopeInterceptor delegate) {
      if (delegate != null) {
        this.delegate = delegate;
      } else {
        this.delegate = new TerminalScopeInterceptor();
      }
    }

    abstract static class DelegatingScope implements DDScope {

      protected final DDScope delegate;

      public DelegatingScope(final DDScope delegate) {
        this.delegate = delegate;
      }

      @Override
      public void afterActivated() {
        delegate.afterActivated();
      }

      @Override
      public AgentSpan span() {
        return delegate.span();
      }

      @Override
      public void setAsyncPropagation(final boolean value) {
        delegate.setAsyncPropagation(value);
      }

      @Override
      public TraceScope.Continuation capture() {
        return delegate.capture();
      }

      @Override
      public void close() {
        delegate.close();
      }

      @Override
      public boolean isAsyncPropagating() {
        return delegate.isAsyncPropagating();
      }
    }
  }

  final class TerminalScopeInterceptor implements ScopeInterceptor {
    @Override
    public DDScope handleSpan(final AgentSpan span) {
      return new TerminalScope(span);
    }

    private static class TerminalScope implements DDScope {
      private final AgentSpan span;

      TerminalScope(final AgentSpan span) {
        this.span = span;
      }

      @Override
      public void afterActivated() {}

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
}
