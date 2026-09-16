package datadog.trace.bootstrap.instrumentation.java.concurrent;

import datadog.context.Context;
import datadog.context.ContextContinuation;
import datadog.context.ContextScope;

final class ContinuationClaim implements ContextContinuation {

  public static final ContinuationClaim CLAIMED = new ContinuationClaim();

  @Override
  public ContextContinuation hold() {
    throw new IllegalStateException();
  }

  @Override
  public ContextScope resume() {
    throw new IllegalStateException();
  }

  @Override
  public Context context() {
    throw new IllegalStateException();
  }

  @Override
  public void release() {
    throw new IllegalStateException();
  }
}
