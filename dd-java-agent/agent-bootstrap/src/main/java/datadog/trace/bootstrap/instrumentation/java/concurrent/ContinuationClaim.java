package datadog.trace.bootstrap.instrumentation.java.concurrent;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

final class ContinuationClaim implements AgentScope.Continuation {

  public static final ContinuationClaim CLAIMED = new ContinuationClaim();

  @Override
  public AgentScope.Continuation hold() {
    throw new IllegalStateException();
  }

  @Override
  public AgentScope activate() {
    throw new IllegalStateException();
  }

  @Override
  public AgentSpan span() {
    throw new IllegalStateException();
  }

  @Override
  public void cancel() {
    throw new IllegalStateException();
  }
}
