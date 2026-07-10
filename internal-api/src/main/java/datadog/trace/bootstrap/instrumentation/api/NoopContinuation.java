package datadog.trace.bootstrap.instrumentation.api;

import datadog.context.Context;
import datadog.context.ContextContinuation;
import datadog.context.ContextScope;
import datadog.trace.context.TraceScope;

final class NoopContinuation implements ContextContinuation, TraceScope.Continuation {
  static final NoopContinuation INSTANCE = new NoopContinuation();

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

  @Override
  public TraceScope activate() {
    return NoopScope.INSTANCE;
  }

  @Override
  public void cancel() {}
}
