package datadog.trace.bootstrap.instrumentation.java.concurrent;

public final class ComparableRunnable<T extends Runnable & Comparable<T>> extends Wrapper<T>
    implements Comparable<ComparableRunnable<T>> {

  public ComparableRunnable(T delegate) {
    super(delegate);
  }

  @Override
  public int compareTo(ComparableRunnable<T> o) {
    return delegate.compareTo(o.delegate);
  }
}
