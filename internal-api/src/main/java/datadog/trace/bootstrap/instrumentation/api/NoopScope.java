package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.context.TraceScope;

public final class NoopScope implements AgentScope {
  public static final NoopScope INSTANCE = new NoopScope();

  private NoopScope() {}

  @Override
  public AgentSpan span() {
    return NoopSpan.INSTANCE;
  }

  @Override
  @SuppressWarnings("deprecation")
  public TraceScope.Continuation capture() {
    return NoopContinuation.INSTANCE;
  }

  @Override
  public void close() {}
}
