package datadog.trace.opentelemetry1;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import io.opentelemetry.context.Scope;

public class OtelScope implements Scope {
  private final Scope scope;
  private final AgentScope delegate;

  public OtelScope(Scope scope, AgentScope delegate) {
    this.scope = scope;
    this.delegate = delegate;
  }

  @Override
  public void close() {
    this.delegate.close();
    this.scope.close();
  }
}
