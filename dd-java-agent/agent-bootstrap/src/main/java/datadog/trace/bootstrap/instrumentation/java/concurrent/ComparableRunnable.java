package datadog.trace.bootstrap.instrumentation.java.concurrent;

import datadog.context.ContextContinuation;

public final class ComparableRunnable<T extends Runnable & Comparable<T>> extends Wrapper<T>
    implements Comparable<ComparableRunnable<T>> {

  public ComparableRunnable(T delegate, ContextContinuation continuation) {
    super(delegate, continuation);
  }

  @Override
  public int compareTo(ComparableRunnable<T> o) {
    return delegate.compareTo(o.delegate);
  }
}
