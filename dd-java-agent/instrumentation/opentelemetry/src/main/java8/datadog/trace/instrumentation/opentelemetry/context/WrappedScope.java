package datadog.trace.instrumentation.opentelemetry.context;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import io.opentelemetry.context.Scope;

public class WrappedScope implements Scope {
  private final Scope delegate;
  private final AgentScope agentDelegate;

  public WrappedScope(Scope delegate, AgentScope agentDelegate) {
    this.delegate = delegate;
    this.agentDelegate = agentDelegate;
  }

  @Override
  public void close() {
    agentDelegate.close();
    delegate.close();
  }
}
