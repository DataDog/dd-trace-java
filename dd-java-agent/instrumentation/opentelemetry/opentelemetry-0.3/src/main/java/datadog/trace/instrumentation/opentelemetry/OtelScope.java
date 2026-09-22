package datadog.trace.instrumentation.opentelemetry;

import datadog.context.ContextScope;
import datadog.trace.context.TraceScope;
import io.opentelemetry.context.Scope;

public class OtelScope implements Scope, TraceScope {
  private final ContextScope delegate;

  OtelScope(final ContextScope delegate) {
    this.delegate = delegate;
  }

  @Override
  public void close() {
    delegate.close();
  }
}
