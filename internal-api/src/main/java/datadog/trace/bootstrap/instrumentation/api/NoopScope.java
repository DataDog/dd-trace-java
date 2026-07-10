package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.context.TraceScope;

final class NoopScope implements AgentScope {
  static final NoopScope INSTANCE = new NoopScope();

  private NoopScope() {}

  @Override
  public AgentSpan span() {
    return NoopSpan.INSTANCE;
  }

  @Override
  public TraceScope.Continuation capture() {
    return NoopContinuation.INSTANCE;
  }

  @Override
  public void close() {}
}
