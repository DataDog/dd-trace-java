package datadog.trace.instrumentation.opentelemetry14;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AttachableWrapper;
import io.opentelemetry.context.Scope;

public class OtelScope implements Scope {
  private final Scope scope;
  private final AgentScope delegate;

  public OtelScope(Scope scope, AgentScope delegate) {
    this.scope = scope;
    this.delegate = delegate;
    if (delegate instanceof AttachableWrapper) {
      ((AttachableWrapper) delegate).attachWrapper(this);
    }
  }

  @Override
  public void close() {
    this.delegate.close();
    this.scope.close();
  }
}
