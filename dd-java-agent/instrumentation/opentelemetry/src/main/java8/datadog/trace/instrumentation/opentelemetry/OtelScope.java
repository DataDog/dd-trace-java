package datadog.trace.instrumentation.opentelemetry;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import io.opentelemetry.context.Scope;

public final class OtelScope implements Scope {
  private final AgentScope delegate;

  OtelScope(final AgentScope delegate) {
    this.delegate = delegate;
  }

  @Override
  public void close() {
    delegate.close();
  }

  public AgentScope getDelegate() {
    return delegate;
  }
}
