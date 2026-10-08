package datadog.trace.bootstrap.instrumentation.api;

import datadog.context.Context;
import datadog.context.ContextContinuation;
import datadog.context.ContextScope;

public final class NoopContinuation implements ContextContinuation {
  public static final NoopContinuation INSTANCE = new NoopContinuation();

  private NoopContinuation() {}

  @Override
  public NoopContinuation hold() {
    return this;
  }

  @Override
  public ContextScope resume() {
    return NoopScope.INSTANCE;
  }

  @Override
  public Context context() {
    return Context.root();
  }

  @Override
  public void release() {}
}
