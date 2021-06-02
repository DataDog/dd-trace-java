package datadog.trace.bootstrap.instrumentation.java.concurrent;

import datadog.trace.context.TraceScope;

final class ContinuationClaim implements TraceScope.Continuation {

  public static final ContinuationClaim CLAIMED = new ContinuationClaim();

  @Override
  public TraceScope activate() {
    throw new IllegalStateException();
  }

  @Override
  public void cancel() {
    throw new IllegalStateException();
  }
}
