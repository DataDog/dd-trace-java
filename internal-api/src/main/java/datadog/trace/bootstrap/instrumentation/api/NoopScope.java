package datadog.trace.bootstrap.instrumentation.api;

import datadog.context.Context;
import datadog.context.ContextScope;

public final class NoopScope implements ContextScope {
  public static final NoopScope INSTANCE = new NoopScope();

  private NoopScope() {}

  @Override
  public Context context() {
    return NoopSpan.INSTANCE;
  }

  @Override
  public void close() {}
}
