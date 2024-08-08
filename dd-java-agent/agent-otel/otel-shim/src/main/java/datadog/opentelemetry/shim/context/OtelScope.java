package datadog.opentelemetry.shim.context;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AttachableWrapper;
import io.opentelemetry.context.Scope;

public class OtelScope implements Scope {
  private final Scope scope;
  private final AgentScope delegate;
  private final Object[] contextEntries;

  public OtelScope(Scope scope, AgentScope delegate, Object[] contextEntries) {
    this.scope = scope;
    this.delegate = delegate;
    this.contextEntries = contextEntries;
    if (delegate instanceof AttachableWrapper) {
      ((AttachableWrapper) delegate).attachWrapper(this);
    }
  }

  /** Context entries from {@link OtelContext}, captured when the context was made current. */
  Object[] contextEntries() {
    return contextEntries;
  }

  @Override
  public void close() {
    this.delegate.close();
    this.scope.close();
  }
}
