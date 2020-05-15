package datadog.trace.core.scopemanager;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.context.TraceScope;

class DelegatingScope implements AgentScope {

  protected final AgentScope delegate;

  public DelegatingScope(final AgentScope delegate) {
    this.delegate = delegate;
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
