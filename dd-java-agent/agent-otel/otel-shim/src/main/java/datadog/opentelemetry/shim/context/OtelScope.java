package datadog.opentelemetry.shim.context;

import datadog.context.ContextScope;
import io.opentelemetry.context.Scope;

public class OtelScope implements Scope {
  private final Scope scope;
  private final ContextScope delegate;

  public OtelScope(Scope scope, ContextScope delegate) {
    this.scope = scope;
    this.delegate = delegate;
  }

  @Override
  public void close() {
    delegate.close();
    scope.close();
  }
}
