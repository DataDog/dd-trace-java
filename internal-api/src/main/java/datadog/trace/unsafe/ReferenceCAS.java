package datadog.trace.unsafe;

public interface ReferenceCAS<T> {
  boolean compareAndSet(Object holder, T expected, T newValue);
}
