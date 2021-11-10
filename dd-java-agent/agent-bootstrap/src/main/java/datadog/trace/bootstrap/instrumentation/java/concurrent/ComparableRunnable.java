package datadog.trace.bootstrap.instrumentation.java.concurrent;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;

public final class ComparableRunnable<T extends Runnable & Comparable<T>> extends Wrapper<T>
    implements Comparable<ComparableRunnable<T>> {

  public ComparableRunnable(T delegate, AgentScope.Continuation continuation) {
    super(delegate, continuation);
  }

  @Override
  public int compareTo(ComparableRunnable<T> o) {
    return delegate.compareTo(o.delegate);
  }
}
