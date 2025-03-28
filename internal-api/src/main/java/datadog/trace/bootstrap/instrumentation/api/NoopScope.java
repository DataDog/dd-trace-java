package datadog.trace.bootstrap.instrumentation.api;

import datadog.context.Context;

final class NoopScope implements AgentScope {
  static final NoopScope INSTANCE = new NoopScope();

  private NoopScope() {}

  @Override
  public Context context() {
    return Context.root();
  }

  @Override
  public AgentSpan span() {
    return NoopSpan.INSTANCE;
  }

  @Override
  public Continuation capture() {
    return NoopContinuation.INSTANCE;
  }

  @Override
  public void close() {}
}
