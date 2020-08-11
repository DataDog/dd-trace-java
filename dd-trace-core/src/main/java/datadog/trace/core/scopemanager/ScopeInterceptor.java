package datadog.trace.core.scopemanager;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.SubTrace;
import datadog.trace.context.TraceScope;

/**
 * This interface provides the needed structure to allow various actions to be performed when a
 * scope is acted upon. This is generally done by wrapping the returned scope with a custom scope
 * that applies specific logic on close. Each overridden method must call the delegate or super. The
 * ScopeManger responsible for the thread local (ContinuableScopeManager) should be the first in the
 * chain, otherwise any wrapped scope would be not associated with the thread local.
 */
interface ScopeInterceptor {
  Scope handleSpan(AgentSpan span);

  /**
   * Interface used by ScopeInterceptor implementations. Provides a hook to invoke functionality
   * after a scope is activated, instead of before (at construction time).
   */
  interface Scope extends AgentScope {
    void afterActivated();
  }

  abstract class DelegatingInterceptor implements ScopeInterceptor {
    protected final ScopeInterceptor delegate;

    /**
     * @param delegate - next interceptor in the chain. A null value implies the end of the chain
     *     and will automatically be set to a TerminalScopeInterceptor.
     */
    protected DelegatingInterceptor(final ScopeInterceptor delegate) {
      if (delegate != null) {
        this.delegate = delegate;
      } else {
        this.delegate = new TerminalInterceptor();
      }
    }
  }

  /** This is a helper class to reduce the code needed for each ScopeInterceptor implementation. */
  abstract class DelegatingScope implements Scope {

    protected final Scope delegate;

    public DelegatingScope(final Scope delegate) {
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

    @Override
    public SubTrace.Context subTraceContext() {
      return delegate.subTraceContext();
    }
  }

  /** This class is intended to be the final scope in the chain. */
  final class TerminalInterceptor implements ScopeInterceptor {
    @Override
    public Scope handleSpan(final AgentSpan span) {
      return new TerminalScope(span);
    }

    /**
     * This class should only be used to hold the span after a chain of wrapped scopes. Only the
     * span() method should ever be actually called here.
     */
    private static class TerminalScope implements Scope {
      private final AgentSpan span;
      private final SubTrace.Context subTraceContext;

      TerminalScope(final AgentSpan span) {
        this.span = span;
        subTraceContext = new SubTrace.Context(span);
      }

      @Override
      public void afterActivated() {}

      @Override
      public AgentSpan span() {
        return span;
      }

      @Override
      public SubTrace.Context subTraceContext() {
        return subTraceContext;
      }

      @Override
      public void setAsyncPropagation(final boolean value) {}

      @Override
      public TraceScope.Continuation capture() {
        return null;
      }

      @Override
      public void close() {
        subTraceContext.close();
      }

      @Override
      public boolean isAsyncPropagating() {
        return false;
      }
    }
  }
}
