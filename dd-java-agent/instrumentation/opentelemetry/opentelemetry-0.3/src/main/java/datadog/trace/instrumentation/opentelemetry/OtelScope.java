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
  public void close() {
    delegate.close();
  }
}
