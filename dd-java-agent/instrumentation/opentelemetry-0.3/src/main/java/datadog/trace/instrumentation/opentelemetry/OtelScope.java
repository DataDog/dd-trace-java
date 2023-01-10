package datadog.trace.instrumentation.opentelemetry;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.context.TraceScope;
import io.opentelemetry.context.Scope;

public class OtelScope implements Scope, TraceScope {
  private final AgentScope delegate;

  OtelScope(final AgentScope delegate) {
    this.delegate = delegate;
  }

  @Override
  public Continuation capture() {
    return delegate.capture();
  }

  @Override
  public Continuation captureConcurrent() {
    return delegate.captureConcurrent();
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
  public void setAsyncPropagation(final boolean value) {
    delegate.setAsyncPropagation(value);
  }
}
